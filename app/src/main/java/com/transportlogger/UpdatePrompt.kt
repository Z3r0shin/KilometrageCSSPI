package com.transportlogger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The update conversation: "a new version is out", then the download, then
 * Android's own install screen. Two ways in: quietly when the app opens (at
 * most once a day, silent unless there is something new), or on demand from
 * the Réglages.
 */
object UpdatePrompt {

    fun checkQuietly(activity: Activity) {
        if (!Updater.dueForCheck(activity)) return
        Thread {
            val release = runCatching { Updater.newerRelease(activity) }.getOrNull() ?: return@Thread
            activity.runOnUiThread { if (alive(activity)) offer(activity, release) }
        }.start()
    }

    /** [onDone] runs on the main thread once the answer is in. */
    fun checkNow(activity: Activity, onDone: () -> Unit = {}) {
        Thread {
            val result = runCatching { Updater.newerRelease(activity) }
            activity.runOnUiThread {
                onDone()
                if (!alive(activity)) return@runOnUiThread
                result.fold(
                    onSuccess = { release ->
                        if (release != null) offer(activity, release)
                        else Toast.makeText(
                            activity,
                            "L'application est à jour (version ${Updater.installedVersion(activity)}).",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { error(activity, it) }
                )
            }
        }.start()
    }

    private fun offer(activity: Activity, release: Updater.Release) {
        val notes = release.notes.take(600).let { if (release.notes.length > 600) "$it…" else it }
        val size = if (release.size > 0) String.format(Fmt.FR, " (%.1f Mo)", release.size / 1_000_000.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("Mise à jour disponible")
            .setMessage(
                "La version ${release.version} est prête$size. Vous avez la " +
                    "${Updater.installedVersion(activity)}. Vos trajets, lieux et réglages sont conservés." +
                    if (notes.isNotBlank()) "\n\n$notes" else ""
            )
            .setPositiveButton("Installer") { _, _ -> install(activity, release) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    /** A release waiting for the user to allow installs from this app. */
    private var waitingForPermission: Updater.Release? = null

    /**
     * Android only lets an app install packages once the user allows it, in a
     * settings page. Ask first, with our own words, rather than download and
     * lose the install on the way back from that page.
     */
    private fun install(activity: Activity, release: Updater.Release) {
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
            download(activity, release)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Une autorisation d'abord")
            .setMessage(
                "Android doit permettre à l'application d'installer ses propres mises à jour. " +
                    "Activez « Autoriser cette source », puis revenez : la mise à jour continuera."
            )
            .setPositiveButton("Ouvrir les réglages") { _, _ ->
                waitingForPermission = release
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Call from onResume: picks the update back up once installs are allowed. */
    fun resume(activity: Activity) {
        val release = waitingForPermission ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        waitingForPermission = null
        download(activity, release)
    }

    private fun download(activity: Activity, release: Updater.Release) {
        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.Text_Body)
            text = "Téléchargement…"
        }
        val bar = LinearProgressIndicator(activity).apply {
            isIndeterminate = release.size <= 0
            max = 100
            setIndicatorColor(activity.getColor(R.color.primary))
            trackColor = activity.getColor(R.color.surface_inset)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * density).toInt() })
        }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Version ${release.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Annuler") { _, _ -> cancelled.set(true) }
            .show()

        Thread {
            try {
                val apk = Updater.download(activity, release, { pct ->
                    activity.runOnUiThread {
                        bar.setProgressCompat(pct, true)
                        label.text = "Téléchargement… $pct %"
                    }
                }, { cancelled.get() })
                activity.runOnUiThread { label.text = "Installation…" }
                Updater.install(activity, apk)
                activity.runOnUiThread { if (alive(activity) && dialog.isShowing) dialog.dismiss() }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (alive(activity) && dialog.isShowing) dialog.dismiss()
                    if (!cancelled.get() && alive(activity)) error(activity, e)
                }
            }
        }.start()
    }

    private fun error(activity: Activity, e: Throwable) {
        val msg = (e as? Updater.UpdateException)?.message ?: "La mise à jour a échoué."
        AlertDialog.Builder(activity)
            .setTitle("Mise à jour")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun alive(a: Activity) = !a.isFinishing && !a.isDestroyed
}
