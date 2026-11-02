package com.transportlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Android's answer to an update [Updater.install] handed over. Usually it asks
 * the user to confirm: show that screen. On success the app is replaced and
 * [BootReceiver] picks logging back up; there is nothing to do here.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(ctx, "Mise à jour annulée.", Toast.LENGTH_SHORT).show()
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    ctx, "La mise à jour a échoué" + (why?.let { " : $it" } ?: "."), Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
