package com.transportlogger

import android.content.Context
import android.content.Intent

/**
 * v0.6 — the decision brain, simplified: NO MORE CONFIRMATIONS.
 * Detection proved accurate enough, so every leg logs automatically.
 *
 *   origin = the place the current leg starts from (null = day not started)
 *
 * Rules:
 *   - Commits are IGNORED outside the work window / on days off.
 *   - First stop of the day just becomes the origin (no leg).
 *   - Every next distinct stop logs origin -> stop immediately, then becomes
 *     the new origin.
 *   - Home is an invisible bridge: a stop there is never logged and leaves the
 *     origin alone, so A -> home -> B (lunch at home) still logs A -> B.
 *   - endOfDay() — called at the window end by the service — resets for tomorrow.
 */
object TripEngine {

    private const val PREFS = "logger_state"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The origin is only meaningful for the day it was set on. endOfDay() clears
     * it, but the service has to be alive at the window end to run — so a stale
     * origin survives a crash or a reboot. Date-stamping it means a leg is never
     * logged from a place left days ago.
     */
    private fun origin(ctx: Context): String? {
        val p = prefs(ctx)
        if (p.getString("originDate", null) != Settings.todayKey()) return null
        return p.getString("origin", null)
    }

    private fun setOrigin(ctx: Context, v: String?) =
        prefs(ctx).edit()
            .putString("origin", v)
            .putString("originDate", if (v == null) null else Settings.todayKey())
            .apply()

    /** Called by the service when a zone has been dwelled in long enough to commit. */
    fun onZoneCommitted(ctx: Context, place: Place) {
        if (!Settings.inWindow(ctx)) return

        if (place.isHome) return      // never logged, and the origin survives it

        val org = origin(ctx)
        if (org == null) { setOrigin(ctx, place.name); return }
        if (place.name == org) return          // still at the origin

        TripStore(ctx).add(org, place.name, KmLookup.km(ctx, org, place.name), "CONFIRMÉ")
        ctx.sendBroadcast(
            Intent(LocationService.ACTION_TRIPS_CHANGED).setPackage(ctx.packageName)
        )
        setOrigin(ctx, place.name)
    }

    /** Window end — reset. Tomorrow starts clean. */
    fun endOfDay(ctx: Context) {
        setOrigin(ctx, null)
    }
}
