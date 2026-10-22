package com.transportlogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Aujourd'hui: one glance answers "est-ce que ça tourne, où suis-je, où en est
 * la journée, qu'est-ce qui a été consigné ?"
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        /** Service messages that explain a missing position, shown as they are. */
        val DIAGNOSTICS = listOf("Signal faible", "Permission de localisation manquante")
    }

    private lateinit var switchTracking: MaterialSwitch
    private lateinit var stateDot: View
    private lateinit var stateLabel: TextView
    private lateinit var placeBlock: View
    private lateinit var placeLabel: TextView
    private lateinit var placeName: TextView
    private lateinit var confirmedIcon: View
    private lateinit var statusLine: TextView
    private lateinit var windowBar: LinearProgressIndicator
    private lateinit var windowStart: TextView
    private lateinit var windowCaption: TextView
    private lateinit var windowEnd: TextView
    private lateinit var statToday: TextView
    private lateinit var statTodayCaption: TextView
    private lateinit var statMonth: TextView
    private lateinit var statMonthCaption: TextView
    private lateinit var tripsEmpty: View
    private lateinit var tripRows: LinearLayout

    private val poSheet = PoSheet(this)
    private var diagnostic: String? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshWindowBar(); ticker.postDelayed(this, 30_000L) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                LocationService.ACTION_STATUS -> {
                    diagnostic = i.getStringExtra(LocationService.EXTRA_MSG)
                        ?.takeIf { msg -> DIAGNOSTICS.any { msg.startsWith(it) } }
                    refreshStateLabels()
                }
                LocationService.ACTION_TRIPS_CHANGED -> refreshTrips()
            }
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            maybeRequestBackground()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First launch: nothing runs until we know who files the claim.
        if (!Profile.load(this).complete) {
            startActivity(
                Intent(this, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_FIRST_RUN, true)
            )
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        Ui.edgeToEdge(this, findViewById(R.id.root), padBottom = false)
        Nav.attach(this, R.id.nav_today)

        switchTracking = findViewById(R.id.switchTracking)
        stateDot = findViewById(R.id.stateDot)
        stateLabel = findViewById(R.id.stateLabel)
        placeBlock = findViewById(R.id.placeBlock)
        placeLabel = findViewById(R.id.placeLabel)
        placeName = findViewById(R.id.placeName)
        confirmedIcon = findViewById(R.id.confirmedIcon)
        statusLine = findViewById(R.id.statusLine)
        windowBar = findViewById(R.id.windowBar)
        windowStart = findViewById(R.id.windowStart)
        windowCaption = findViewById(R.id.windowCaption)
        windowEnd = findViewById(R.id.windowEnd)
        statToday = findViewById(R.id.statToday)
        statTodayCaption = findViewById(R.id.statTodayCaption)
        statMonth = findViewById(R.id.statMonth)
        statMonthCaption = findViewById(R.id.statMonthCaption)
        tripsEmpty = findViewById(R.id.tripsEmpty)
        tripRows = findViewById(R.id.tripRows)

        switchTracking.setOnCheckedChangeListener { _, checked ->
            if (checked == isRunning()) return@setOnCheckedChangeListener
            if (checked) start() else stopTracking()
        }

        // Keeps the press ripples inside the rounded corners.
        findViewById<View>(R.id.tripCard).clipToOutline = true
        findViewById<View>(R.id.btnCalendar).apply {
            clipToOutline = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CalendarActivity::class.java))
            }
        }
        // Through the bar itself, so the link and the Trajets tab cannot drift apart.
        findViewById<View>(R.id.linkMonth).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_trips
        }
    }

    override fun onResume() {
        super.onResume()
        Nav.sync(this, R.id.nav_today)
        val filter = IntentFilter().apply {
            addAction(LocationService.ACTION_STATUS)
            addAction(LocationService.ACTION_TRIPS_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The service may have been killed (reboot, OEM battery saver, reinstall)
        // while the flag stayed on. Opening the app re-arms it.
        LocationService.ensureRunning(this)
        switchTracking.isChecked = isRunning()
        // Whatever was said before the pause is stale; the next broadcast repeats it.
        diagnostic = null
        findViewById<TextView>(R.id.todayLabel).text = Fmt.day(Settings.todayKey())
        refreshTrips()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ---- Tracking control ----

    private fun isRunning() = LocationService.isEnabled(this)

    private fun start() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            switchTracking.isChecked = false
            return
        }
        maybeRequestBackground()
        LocationService.enable(this)
        refreshStateLabels()
    }

    private fun stopTracking() {
        LocationService.disable(this)
        diagnostic = null
        refreshStateLabels()
    }

    private fun maybeRequestBackground() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setMessage("Choisissez « Toujours autoriser » la localisation pour détecter les arrêts en conduisant, même écran éteint.")
                .setPositiveButton("Ouvrir") { _, _ ->
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }

    // ---- Status card ----

    private fun refreshStateLabels() {
        val running = isRunning()
        val offToday = Settings.isDayOff(this, Settings.todayKey())
        val active = running && Settings.inWindow(this)
        stateLabel.text = when {
            !running -> "Détection arrêtée"
            offToday -> "En congé aujourd'hui"
            active -> "Détection active"
            else -> "En veille"
        }
        stateDot.isActivated = active

        // Outside an active window the service's last position means nothing.
        val live = if (active) LocationService.lastStatus else null
        val zone = live?.zone
        placeBlock.visibility = if (live != null) View.VISIBLE else View.GONE
        placeLabel.text = if (zone != null) "Vous êtes à" else "Vous êtes"
        placeName.text = zone ?: "Entre deux lieux"
        // Between places the service reports "committed" too, hence the zone test.
        confirmedIcon.visibility =
            if (zone != null && live?.committed == true) View.VISIBLE else View.GONE
        statusLine.text = when {
            !running -> "Activez la détection pour consigner vos trajets."
            offToday -> "Aucune détection aujourd'hui."
            !active -> "La détection reprend à ${Settings.fmt(Settings.startMinutes(this))}."
            live == null -> diagnostic ?: "Recherche de la position…"
            zone == null -> "Le prochain arrêt sera consigné."
            live.committed -> "Arrêt confirmé"
            else -> "Arrêt en cours · ${seconds(live.dwellS)} sur ${seconds(live.neededS)}"
        }
    }

    private fun refreshWindowBar() {
        val s = Settings.startMinutes(this)
        val e = Settings.endMinutes(this)
        windowStart.text = Settings.fmt(s)
        windowEnd.text = Settings.fmt(e)
        val n = Settings.nowMinutes()
        val offToday = Settings.isDayOff(this, Settings.todayKey())
        val pct = when {
            offToday || n <= s -> 0
            n >= e -> 100
            else -> ((n - s) * 100.0 / (e - s)).toInt()
        }
        windowBar.setProgressCompat(pct, true)
        windowCaption.text = when {
            offToday -> ""
            n < s -> "début dans ${minutes(s - n)}"
            n < e -> "fin de journée dans ${minutes(e - n)}"
            else -> "journée terminée"
        }
        refreshStateLabels()
    }

    /** "40 s", "2 min", "1 min 20". */
    private fun seconds(s: Long): String = when {
        s < 60 -> "$s s"
        s % 60 == 0L -> "${s / 60} min"
        else -> String.format(Fmt.FR, "%d min %02d", s / 60, s % 60)
    }

    /** "25 min", "2 h", "2 h 25". */
    private fun minutes(m: Int): String = when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> String.format(Fmt.FR, "%d h %02d", m / 60, m % 60)
    }

    // ---- Stats and today's trips ----

    private fun refreshTrips() {
        val store = TripStore(this)
        val today = Settings.todayKey()
        val lines = FormulaireExport.lines(store.trips.filter { it.date == today }.sortedBy { it.ts })
        val monthKm = FormulaireExport.lines(store.tripsForMonth(today.substring(0, 7)).sortedBy { it.ts })
            .sumOf { it.total ?: 0.0 }

        statToday.text = Fmt.km(lines.sumOf { it.total ?: 0.0 })
        statTodayCaption.text =
            "aujourd'hui · ${lines.size} ${if (lines.size > 1) "trajets" else "trajet"}"
        statMonth.text = Fmt.money(monthKm)
        statMonthCaption.text =
            "${SimpleDateFormat("MMMM", Fmt.FR).format(Date())} · ${Fmt.km(monthKm)}"

        tripsEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        tripRows.removeAllViews()
        lines.forEachIndexed { i, line ->
            if (i > 0) tripRows.addView(divider())
            val row = layoutInflater.inflate(R.layout.item_trip_line, tripRows, false)
            TripRow.bind(row, line, onPo = { poSheet.show(it) { refreshTrips() } }, onMore = null)
            tripRows.addView(row)
        }
    }

    private fun divider(): View {
        val d = resources.displayMetrics.density
        return View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, d.toInt())
            ).apply {
                marginStart = (16 * d).toInt()
                marginEnd = (16 * d).toInt()
            }
        }
    }
}
