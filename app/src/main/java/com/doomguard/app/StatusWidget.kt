package com.doomguard.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: today's total screen time, the three most-used apps with a bar against their limit, and one
 * status line (focus time left, or the limited app closest to its limit). The monitor service refreshes it every
 * minute from the usage it already records, and the refresh icon does it on demand.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Store.init(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, build(context, heightOf(appWidgetManager, it))) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        Store.init(context)
        mgr.updateAppWidget(id, build(context, heightOf(mgr, id)))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.doomguard.app.action.REFRESH_WIDGET"

        private val ROWS = listOf(
            intArrayOf(R.id.row1, R.id.row1_icon, R.id.row1_name, R.id.row1_time, R.id.row1_bar, R.id.row1_bar_over),
            intArrayOf(R.id.row2, R.id.row2_icon, R.id.row2_name, R.id.row2_time, R.id.row2_bar, R.id.row2_bar_over),
            intArrayOf(R.id.row3, R.id.row3_icon, R.id.row3_name, R.id.row3_time, R.id.row3_bar, R.id.row3_bar_over)
        )

        fun refresh(ctx: Context) {
            try {
                Store.init(ctx)
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
                if (ids.isEmpty()) return
                ids.forEach { mgr.updateAppWidget(it, build(ctx, heightOf(mgr, it))) }
            } catch (e: Exception) {
                android.util.Log.w("DoomGuard", "widget refresh failed", e)
            }
        }

        /** Current height of a placed widget in dp (it changes when the user resizes it). */
        private fun heightOf(mgr: AppWidgetManager, id: Int): Int =
            mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)

        private fun iconBitmap(ctx: Context, pkg: String): Bitmap? = try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            bmp
        } catch (e: Exception) {
            null
        }

        private fun statusLine(ctx: Context, used: Map<String, Long>): String {
            val now = System.currentTimeMillis()
            if (Store.focusSessionActive()) {
                val mins = ((Store.focusSessionEnd - now) / 60_000L).coerceAtLeast(0)
                val what = Store.focusSessionLabel
                return "Focus session · $mins min left" + if (what.isNotBlank()) " · $what" else ""
            }
            val limits = Store.limitsToday()
            if (limits.isEmpty()) return "No apps limited yet. Open DoomGuard to choose some."
            val worst = limits.entries.maxByOrNull { e -> (used[e.key] ?: 0L).toDouble() / (e.value * 60_000.0) }!!
            val left = worst.value * 60_000L - (used[worst.key] ?: 0L)
            val cap = Store.totalCapMin
            val totalNote = if (cap > 0) {
                val total = limits.keys.sumOf { used[it] ?: 0L }
                " · total ${fmtDurationShort(total)} of ${fmtMinutes(cap)}"
            } else ""
            val name = appLabel(ctx, worst.key)
            return (if (left <= 0) "$name: limit reached" else "$name: ${fmtDurationShort(left)} left") + totalNote
        }

        private fun build(ctx: Context, heightDp: Int = 110): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_status)
            val used = Store.usageDay(today())
            val home = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
            val skip = setOfNotNull(home, ctx.packageName)
            val list = used.filter { it.key !in skip && it.value >= 60_000L }
                .entries.sortedByDescending { it.value }
            val total = list.sumOf { it.value }
            val limits = Store.limitsToday()
            val topUsed = list.firstOrNull()?.value ?: 1L
            // Small widget: two apps and the status line. Taller: three apps. Very short: just the apps.
            val maxRows = if (heightDp >= 170) 3 else 2

            views.setTextViewText(R.id.widget_total, if (total < 60_000L) "0m" else fmtDuration(total))

            ROWS.forEachIndexed { i, r ->
                val e = if (i < maxRows) list.getOrNull(i) else null
                if (e == null) {
                    views.setViewVisibility(r[0], View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(r[0], View.VISIBLE)
                val limitMs = (limits[e.key] ?: 0) * 60_000L
                val over = limitMs > 0 && e.value >= limitMs
                val frac = if (limitMs > 0) e.value.toDouble() / limitMs else e.value.toDouble() / topUsed
                val progress = (frac * 1000).toInt().coerceIn(15, 1000)
                val bmp = iconBitmap(ctx, e.key)
                if (bmp != null) views.setImageViewBitmap(r[1], bmp) else views.setImageViewResource(r[1], R.drawable.ic_stat)
                views.setTextViewText(r[2], appLabel(ctx, e.key))
                views.setTextViewText(
                    r[3],
                    fmtDuration(e.value) + if (limitMs > 0) " / " + fmtDuration(limitMs) else ""
                )
                views.setTextColor(r[3], if (over) 0xFFFF8A80.toInt() else 0xFFC9D1DE.toInt())
                views.setProgressBar(r[4], 1000, progress, false)
                views.setProgressBar(r[5], 1000, progress, false)
                views.setViewVisibility(r[4], if (over) View.GONE else View.VISIBLE)
                views.setViewVisibility(r[5], if (over) View.VISIBLE else View.GONE)
            }
            views.setViewVisibility(R.id.widget_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_sub, statusLine(ctx, used))
            views.setViewVisibility(R.id.widget_sub, if (heightDp >= 100) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, StatusWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            return views
        }
    }
}
