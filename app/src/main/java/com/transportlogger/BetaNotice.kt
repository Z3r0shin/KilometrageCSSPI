package com.transportlogger

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/** A one-time note on first use that the app is a beta and feedback is welcome. */
object BetaNotice {

    private const val PREFS = "notices"
    private const val KEY = "betaShown"

    fun showOnce(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        AlertDialog.Builder(activity)
            .setMessage(
                "Yo! L'app est en béta, si vous voyez des affaires qui marchent pas/pourraient " +
                    "marcher mieux, dites-le moi!"
            )
            .setPositiveButton("OK", null)
            .show()
        prefs.edit().putBoolean(KEY, true).apply()
    }
}
