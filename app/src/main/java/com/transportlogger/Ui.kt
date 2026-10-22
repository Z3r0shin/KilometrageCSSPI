package com.transportlogger

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * v0.5 — edge-to-edge fix. Android 15+ draws apps under the status bar,
 * camera cutout and navigation bar; without this, the top of the UI is
 * hidden by the cutout and the bottom by the nav bar. Call once per
 * activity on the root view: it pads the content by the real system bars.
 */
object Ui {

    fun edgeToEdge(activity: Activity, root: View, padBottom: Boolean = true) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, if (padBottom) bars.bottom else 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
