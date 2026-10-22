package com.transportlogger

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The bottom bar shared by the four tab screens.
 *
 * MainActivity stays at the bottom of the stack and at most one other tab sits
 * on top of it, so Back from any tab returns to Aujourd'hui and leaves the app
 * from there. Tabs switch without animation so the bar appears to stay put.
 */
object Nav {

    fun attach(activity: Activity, current: Int) {
        val bar = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        bar.selectedItemId = current
        bar.setOnItemSelectedListener { item ->
            if (item.itemId != current) open(activity, item.itemId)
            // The bar we are leaving keeps showing its own tab.
            item.itemId == current
        }
    }

    /** Re-select this screen's tab; a returning screen may have had another one tapped. */
    fun sync(activity: Activity, current: Int) {
        val bar = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        if (bar.selectedItemId != current) bar.selectedItemId = current
    }

    private fun open(from: Activity, itemId: Int) {
        val target = when (itemId) {
            R.id.nav_trips -> TripsActivity::class.java
            R.id.nav_places -> PlacesActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            else -> null                      // Aujourd'hui: already underneath
        }
        if (target != null) {
            from.startActivity(Intent(from, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        if (from !is MainActivity) from.finish()
        @Suppress("DEPRECATION")
        from.overridePendingTransition(0, 0)
    }
}
