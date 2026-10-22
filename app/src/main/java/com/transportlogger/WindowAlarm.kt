package com.transportlogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * Wakes the service at the next work-window boundary (start or end).
 *
 * A Handler delay counts uptime, which stops while the phone is in deep sleep,
 * so an overnight "wake at 07:15" fired hours late, or only when the app was
 * opened. An exact alarm is set on the wall clock and is allowed to wake the
 * phone out of Doze.
 */
class WindowAlarm : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        // The alarm's own wake lock ends with onReceive; keep the CPU up until
        // the service has re-evaluated its mode and asked for location.
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kilometrage:window")
            .acquire(30_000L)
        LocationService.ensureRunning(ctx)
    }

    companion object {
        private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WindowAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Replace any pending alarm with one [delayMs] from now. */
        fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delayMs
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            } else {
                // Without the permission: still wakes from Doze, within minutes.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            }
        }

        fun cancel(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
        }
    }
}
