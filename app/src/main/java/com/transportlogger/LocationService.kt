package com.transportlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

/**
 * Foreground service with a hard work window.
 *
 * INSIDE 07:30–16:15 : location updates every ~20 s, zone evaluation, prompts.
 * OUTSIDE the window : location updates are REMOVED entirely (no GPS at all),
 *                      the notification says "En veille", and a [WindowAlarm]
 *                      wakes the service at the next boundary.
 * AT 16:15           : TripEngine.endOfDay() auto-closes any unanswered leg.
 *
 * False-positive defences (v0.4):
 *  - tighter default radius (70 m, editable per zone),
 *  - GPS fixes with accuracy worse than MAX_ACCURACY_M are ignored,
 *  - zone switching needs a clearly-closer zone on 2 consecutive fixes.
 */
class LocationService : Service() {

    /** Where the last usable fix put us; [zone] is null between places. */
    data class LiveStatus(
        val zone: String?, val dwellS: Long, val neededS: Long, val committed: Boolean
    )

    companion object {
        const val CH_SERVICE = "logger_service"
        const val ACTION_STATUS = "com.transportlogger.STATUS"
        const val ACTION_TRIPS_CHANGED = "com.transportlogger.TRIPS_CHANGED"
        const val EXTRA_MSG = "msg"
        const val NOTIF_ID = 1

        const val HYSTERESIS_M = 20.0      // new zone must be this much closer to steal focus
        const val SWITCH_FIXES = 2         // ...on this many consecutive fixes
        const val MAX_ACCURACY_M = 75f     // a fuzzier fix cannot put us in a zone or take us out of one
        const val WEAK_ACCURACY_M = 150f   // ...but up to this it can show we are still in the one we were in
        const val EXIT_FIXES = 2           // consecutive good fixes outside before a zone is left

        private const val STATE_PREFS = "logger_state"

        /**
         * What the service last reported, for a screen that opens between two
         * broadcasts. Null until the first one, and again once detection sleeps.
         */
        @Volatile var lastStatus: LiveStatus? = null
            private set

        /**
         * Whether the user wants logging on. This is an intent, not a liveness
         * check: the service can die (reboot, OEM kill, reinstall) while the
         * flag stays true, so always pair it with [ensureRunning].
         */
        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("running", false)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("running", enabled).apply()
        }

        /** Turn logging on and start the service. */
        fun enable(ctx: Context) {
            setEnabled(ctx, true)
            ensureRunning(ctx)
        }

        /** Turn logging off and stop the service. */
        fun disable(ctx: Context) {
            setEnabled(ctx, false)
            WindowAlarm.cancel(ctx)
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }

        /**
         * Start the service if the user left logging on. Safe to call repeatedly:
         * if it is already up this just re-runs onStartCommand. Call it from every
         * entry point (app resume, boot) so a killed service self-heals.
         */
        fun ensureRunning(ctx: Context) {
            if (!isEnabled(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
            } catch (e: Exception) {
                // Background start limits: the next app resume will retry.
            }
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var store: PlaceStore

    private var updatesOn = false
    private var currentZone: String? = null
    private var zoneSince = 0L
    private var committedZone: String? = null
    private var switchCandidate: String? = null
    private var switchCount = 0
    private var exitCount = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            res.lastLocation?.let { loc ->
                store.refresh()   // a zone may have been added or moved on a screen
                val weak = loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M
                // Indoors every fix can be fuzzy. One that still falls in the zone
                // we are in counts as staying there; any other is set aside.
                if (weak && !(loc.accuracy <= WEAK_ACCURACY_M && stillInZone(loc.latitude, loc.longitude))) {
                    // Say so rather than going silent, which looks like a dead service.
                    broadcast("Signal faible : ±${loc.accuracy.toInt()} m (ignoré, max ${MAX_ACCURACY_M.toInt()} m)")
                    return
                }
                evaluate(loc.latitude, loc.longitude, weak)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        store = PlaceStore(this)
        createChannels()
        startForeground(NOTIF_ID, serviceNotification("Démarrage…"))
        applyMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Settings may have changed (window/dwell) — re-evaluate the mode.
        applyMode()
        return START_STICKY
    }

    /** Switch between ACTIVE (in window) and SLEEP (outside), then schedule the next check. */
    private fun applyMode() {
        val s = Settings.fmt(Settings.startMinutes(this))
        val e = Settings.fmt(Settings.endMinutes(this))

        if (Settings.inWindow(this)) {
            if (!updatesOn) requestUpdates()
            updateServiceNotification("Actif · fenêtre $s – $e")
        } else {
            if (updatesOn) {
                try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
                updatesOn = false
            }
            TripEngine.endOfDay(this)      // closes any open leg; harmless if none
            resetZoneState()
            updateServiceNotification("En veille · reprise à $s")
            broadcast("En veille — la détection reprend à $s.")
        }
        WindowAlarm.schedule(this, Settings.nextBoundaryDelayMs(this) + 1_000L)
    }

    private fun requestUpdates() {
        // High accuracy forces GNSS instead of network positioning, which only
        // resolves to ~100 m — wider than the 70 m default zone radius and
        // wider than MAX_ACCURACY_M, so balanced fixes were being thrown away.
        // Updates are removed entirely outside the window, so the battery cost
        // is bounded to the work day.
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 20_000L
        ).setMinUpdateIntervalMillis(10_000L).build()
        try {
            fused.requestLocationUpdates(req, callback, mainLooper)
            updatesOn = true
        } catch (e: SecurityException) {
            broadcast("Permission de localisation manquante.")
        }
    }

    private fun stillInZone(lat: Double, lng: Double): Boolean =
        store.placeByName(currentZone)?.contains(lat, lng, Settings.zoneMarginM(this).toDouble()) == true

    /** [weak] fixes only reach here when they fall in the current zone: they prolong a stay, nothing else. */
    private fun evaluate(lat: Double, lng: Double, weak: Boolean = false) {
        if (!Settings.inWindow(this)) return   // guard against a straggler fix
        val now = System.currentTimeMillis()
        val hit = if (weak) null else store.nearestContaining(lat, lng, Settings.zoneMarginM(this).toDouble())
        val nearestName = if (weak) currentZone else hit?.first?.name
        val nearestDist = hit?.second ?: Double.MAX_VALUE

        if (nearestName != currentZone) {
            if (currentZone == null) {
                // entering a zone from nowhere: immediate
                switchTo(nearestName, now)
            } else if (nearestName == null) {
                // One stray fix outside (drift, a reflection off a wall) must not
                // restart the dwell; leaving takes a second one in a row.
                if (++exitCount >= EXIT_FIXES) switchTo(null, now)
            } else {
                exitCount = 0
                // two overlapping zones: require a clearly-closer candidate on
                // SWITCH_FIXES consecutive fixes before re-anchoring
                val curPlace = store.placeByName(currentZone)
                val curDistNow = curPlace?.let {
                    PlaceStore.distanceMeters(lat, lng, it.lat, it.lng)
                } ?: Double.MAX_VALUE

                if (nearestName == switchCandidate) switchCount++ else {
                    switchCandidate = nearestName; switchCount = 1
                }
                if (nearestDist + HYSTERESIS_M <= curDistNow && switchCount >= SWITCH_FIXES) {
                    switchTo(nearestName, now)
                }
            }
        } else {
            switchCandidate = null; switchCount = 0; exitCount = 0
        }

        val dwellMs = if (currentZone != null) now - zoneSince else 0L
        val dwellNeeded = Settings.dwellMs(this)

        if (currentZone != null && committedZone != currentZone && dwellMs >= dwellNeeded) {
            committedZone = currentZone
            store.placeByName(currentZone)?.let { TripEngine.onZoneCommitted(this, it) }
        }

        broadcast(
            LiveStatus(currentZone, dwellMs / 1000, dwellNeeded / 1000, committedZone == currentZone),
            "Zone : ${currentZone ?: "aucune"}" +
                if (currentZone != null)
                    " · ${dwellMs / 1000}s / ${dwellNeeded / 1000}s" +
                    (if (committedZone == currentZone) " ✓" else "")
                else ""
        )
    }

    private fun switchTo(zone: String?, now: Long) {
        currentZone = zone
        zoneSince = now
        committedZone = null
        switchCandidate = null
        switchCount = 0
        exitCount = 0
    }

    private fun resetZoneState() = switchTo(null, 0L)

    // ---- Notifications ----

    private fun serviceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Kilométrage")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, serviceNotification(text))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun broadcast(msg: String) = broadcast(null, msg)

    private fun broadcast(status: LiveStatus?, msg: String) {
        lastStatus = status
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MSG, msg))
    }

    override fun onDestroy() {
        lastStatus = null
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
