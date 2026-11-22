package com.doomguard.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Phone unlocks counted from the usage-event stream. */
class DayStats {
    var unlocks = 0
    var firstUnlock = 0L
}

class DayUsage(val totals: Map<String, Long>, val unlocks: Int, val firstUnlock: Long)

/**
 * Turns Android's usage-event stream into "milliseconds in the foreground today" per app.
 * [refresh] is incremental, so calling it every few seconds is cheap.
 */
class UsageTracker(ctx: Context) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var dayStart = midnight()
    private var lastQuery = dayStart
    private val totals = HashMap<String, Long>()
    private val open = HashMap<String, Long>()
    private val resumed = HashMap<String, HashSet<String>>() // package -> its screens (activities) currently resumed

    val stats = DayStats()

    /** Package currently in the foreground, or null (screen off / unknown). */
    var foreground: String? = null
        private set

    fun refresh(now: Long) {
        val m = midnight(now)
        if (m != dayStart) {
            // New day: keep sessions that are still open, restart them at midnight.
            for (k in open.keys.toList()) open[k] = m
            totals.clear()
            stats.unlocks = 0
            stats.firstUnlock = 0L
            dayStart = m
        }
        foreground = process(usm, lastQuery, now, totals, open, resumed, foreground, stats)
        lastQuery = now
    }

    fun usageMs(pkg: String, now: Long): Long =
        (totals[pkg] ?: 0L) + (open[pkg]?.let { (now - it).coerceAtLeast(0) } ?: 0L)

    fun snapshot(now: Long, pkgs: Collection<String>): Map<String, Long> = pkgs.associateWith { usageMs(it, now) }

    /** Every app seen today with at least [minMs] of foreground time. */
    fun snapshotAll(now: Long, minMs: Long = 60_000L): Map<String, Long> =
        (totals.keys + open.keys).associateWith { usageMs(it, now) }.filterValues { it >= minMs }

    companion object {
        @Suppress("DEPRECATION")
        private fun process(
            usm: UsageStatsManager,
            from: Long,
            to: Long,
            totals: HashMap<String, Long>,
            open: HashMap<String, Long>,
            resumed: HashMap<String, HashSet<String>>,
            fgIn: String?,
            stats: DayStats?
        ): String? {
            var fg = fgIn
            val events = usm.queryEvents(from, to) ?: return fg
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val p = e.packageName ?: ""
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> { // a screen of this app was resumed
                        if (p.isEmpty()) continue
                        // A different app coming to the front means the previous one left, even if its "paused" event is late or missing.
                        val prev = fg
                        if (prev != null && prev != p) closeApp(prev, e.timeStamp, totals, open, resumed)
                        resumed.getOrPut(p) { HashSet() }.add(e.className ?: "")
                        if (!open.containsKey(p)) open[p] = e.timeStamp
                        fg = p
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND, 23 -> { // a screen was paused (2) or stopped (23)
                        if (p.isEmpty()) continue
                        // Only when its LAST screen is gone does the app count as closed. Switching accounts or opening a
                        // notification makes an app resume a new screen and pause the old one in any order; closing on the
                        // old screen's pause would wrongly mark the app as not in use.
                        val screens = resumed[p]
                        screens?.remove(e.className ?: "")
                        if (screens == null || screens.isEmpty()) {
                            closeApp(p, e.timeStamp, totals, open, resumed)
                            if (fg == p) fg = null
                        }
                    }
                    16, 26 -> { // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN: close everything that is open
                        for ((k, s) in open) totals[k] = (totals[k] ?: 0L) + (e.timeStamp - s).coerceAtLeast(0)
                        open.clear()
                        resumed.clear()
                        fg = null
                    }
                    18 -> { // KEYGUARD_HIDDEN: the phone was unlocked
                        if (stats != null) {
                            stats.unlocks++
                            if (stats.firstUnlock == 0L) stats.firstUnlock = e.timeStamp
                        }
                    }
                }
            }
            return fg
        }

        private fun closeApp(
            p: String,
            ts: Long,
            totals: HashMap<String, Long>,
            open: HashMap<String, Long>,
            resumed: HashMap<String, HashSet<String>>
        ) {
            val s = open.remove(p)
            if (s != null) totals[p] = (totals[p] ?: 0L) + (ts - s).coerceAtLeast(0)
            resumed.remove(p)
        }

        /** One-off pass over [from, to]; used by the UI to show today's numbers. */
        fun computeDay(ctx: Context, from: Long, to: Long): DayUsage {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val totals = HashMap<String, Long>()
            val open = HashMap<String, Long>()
            val stats = DayStats()
            process(usm, from, to, totals, open, HashMap(), null, stats)
            for ((k, s) in open) totals[k] = (totals[k] ?: 0L) + (to - s).coerceAtLeast(0)
            return DayUsage(totals, stats.unlocks, stats.firstUnlock)
        }

        fun computeTotals(ctx: Context, from: Long, to: Long): Map<String, Long> =
            computeDay(ctx, from, to).totals
    }
}
