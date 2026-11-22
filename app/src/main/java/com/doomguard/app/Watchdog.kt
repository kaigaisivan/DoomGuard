package com.doomguard.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Phones from some makers kill background apps. This sets an alarm that wakes a receiver every ~15 minutes,
 * which restarts the monitor service if it died, then sets the next alarm. Android cancels alarms when you
 * force-stop an app, so this cannot help in that one case (the Guardian log records the gap instead).
 */
object Watchdog {
    private const val REQUEST = 4711
    private const val INTERVAL_MS = 15 * 60_000L

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQUEST, Intent(ctx, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(ctx: Context, delayMs: Long = INTERVAL_MS) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending(ctx))
        } catch (e: Exception) {
            Log.w("DoomGuard", "could not schedule watchdog", e)
        }
    }

    fun cancel(ctx: Context) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
        } catch (e: Exception) {
            Log.w("DoomGuard", "could not cancel watchdog", e)
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        WebFilter.ensure(context) // brings the website filter back if it was stopped
        if (AutoBackup.due()) {
            val pending = goAsync()
            Thread {
                try { AutoBackup.runIfDue(context) } finally { pending.finish() }
            }.start()
        }
        if (!Store.monitoringEnabled && !Store.webFilterActive()) return
        if (Store.monitoringEnabled && Perms.hasUsage(context)) MonitorService.start(context)
        Watchdog.schedule(context)
    }
}
