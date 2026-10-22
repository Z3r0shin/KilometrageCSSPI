package com.transportlogger

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Calendar

/**
 * Central app settings, stored in SharedPreferences.
 *
 * The work window is the heart of v0.4: NOTHING is detected before start
 * (default 07:30) or after end (default 16:15). Outside the window the
 * service stops requesting location entirely (battery-friendly), and at the
 * end boundary any open leg is auto-closed.
 */
object Settings {

    private const val PREFS = "settings"

    const val DEF_START = 7 * 60 + 30     // 07:30
    const val DEF_END = 16 * 60 + 15      // 16:15
    const val DEF_RADIUS = 70             // metres (was 150 in v0.3 — tightened)
    const val DEF_DWELL_SEC = 150         // 2.5 min
    const val DEF_ZONE_MARGIN = 20        // metres of tolerance around every zone

    private fun p(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Apparence ----
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** Follows the phone unless the user picked a side. */
    fun themeMode(ctx: Context): String =
        p(ctx).getString("themeMode", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(ctx: Context, mode: String) {
        p(ctx).edit().putString("themeMode", mode).apply()
        applyTheme(ctx)
    }

    fun applyTheme(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode(ctx)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ---- Work window ----
    fun startMinutes(ctx: Context): Int = p(ctx).getInt("winStart", DEF_START)
    fun endMinutes(ctx: Context): Int = p(ctx).getInt("winEnd", DEF_END)

    fun setWindow(ctx: Context, startMin: Int, endMin: Int) {
        p(ctx).edit().putInt("winStart", startMin).putInt("winEnd", endMin).apply()
    }

    fun fmt(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** True only between start (inclusive) and end (exclusive), on a working day. */
    fun inWindow(ctx: Context): Boolean {
        if (isDayOff(ctx, todayKey())) return false
        val n = nowMinutes()
        return n >= startMinutes(ctx) && n < endMinutes(ctx)
    }

    // ---- Calendar: days off (vacations, holidays) ----

    fun todayKey(): String {
        val c = Calendar.getInstance()
        return String.format("%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun weekendsOff(ctx: Context): Boolean = p(ctx).getBoolean("weekendsOff", true)
    fun setWeekendsOff(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("weekendsOff", v).apply()

    fun daysOff(ctx: Context): Set<String> =
        p(ctx).getStringSet("daysOff", emptySet()) ?: emptySet()

    fun toggleDayOff(ctx: Context, dateKey: String) {
        val s = daysOff(ctx).toMutableSet()
        if (!s.add(dateKey)) s.remove(dateKey)
        p(ctx).edit().putStringSet("daysOff", s).apply()
    }

    /** dateKey = yyyy-MM-dd. Off if marked, or if weekend and weekends are off. */
    fun isDayOff(ctx: Context, dateKey: String): Boolean {
        if (daysOff(ctx).contains(dateKey)) return true
        if (!weekendsOff(ctx)) return false
        return try {
            val parts = dateKey.split("-").map { it.toInt() }
            val c = Calendar.getInstance()
            c.set(parts[0], parts[1] - 1, parts[2])
            val d = c.get(Calendar.DAY_OF_WEEK)
            d == Calendar.SATURDAY || d == Calendar.SUNDAY
        } catch (e: Exception) { false }
    }

    /** Milliseconds until the next window boundary (start or end of window). */
    fun nextBoundaryDelayMs(ctx: Context): Long {
        val n = nowMinutes()
        val s = startMinutes(ctx)
        val e = endMinutes(ctx)
        val target = when {
            n < s -> s                 // before the window: wait for start
            n < e -> e                 // inside: wait for end
            else -> s + 24 * 60        // after: tomorrow's start
        }
        val secNow = Calendar.getInstance().get(Calendar.SECOND)
        return maxOf(30_000L, (target - n) * 60_000L - secNow * 1000L)
    }

    // ---- Zone radius ----
    fun defaultRadius(ctx: Context): Int = p(ctx).getInt("defRadius", DEF_RADIUS)
    fun setDefaultRadius(ctx: Context, r: Int) {
        p(ctx).edit().putInt("defRadius", r).apply()
    }

    // ---- Zone margin (tolerance around a zone's outline when deciding you are in it) ----
    fun zoneMarginM(ctx: Context): Int = p(ctx).getInt("zoneMargin", DEF_ZONE_MARGIN)
    fun setZoneMarginM(ctx: Context, m: Int) {
        p(ctx).edit().putInt("zoneMargin", m).apply()
    }

    // ---- Dwell (how long you must stay in a zone before it counts) ----
    fun dwellSec(ctx: Context): Int = p(ctx).getInt("dwellSec", DEF_DWELL_SEC)
    fun dwellMs(ctx: Context): Long = dwellSec(ctx) * 1000L
    fun setDwellSec(ctx: Context, s: Int) {
        p(ctx).edit().putInt("dwellSec", s).apply()
    }
}
