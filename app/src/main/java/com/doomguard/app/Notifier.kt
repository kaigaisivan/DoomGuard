package com.doomguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifier {
    const val CH_MON = "monitor"
    const val CH_NUDGE = "nudge"
    const val CH_QUOTE = "quotes"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MON, "Monitoring", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_NUDGE, "Limit warnings", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_QUOTE, "Quotes & reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun foreground(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_MON)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("DoomGuard is running")
            .setContentText("Tap to open")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // nothing shown on the lock screen
            .setContentIntent(openApp(ctx))
            .build()

    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, contentIntent: PendingIntent? = null) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: openApp(ctx))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // notification permission not granted; ignore
        }
    }
}
