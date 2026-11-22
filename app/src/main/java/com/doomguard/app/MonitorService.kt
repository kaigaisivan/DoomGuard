package com.doomguard.app

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Foreground service that polls every few seconds. When a monitored app is in the foreground it decides
 * whether to show [BlockActivity]:
 *  - SESSION: a focus session is running
 *  - LOCKED:  the app is cooling down after too many extensions
 *  - FOCUS:   inside the scheduled focus hours
 *  - LIMIT:   the daily limit is used up
 *  - INTENT:  the app was just opened (the "what are you here for?" pause)
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracker: UsageTracker? = null
    private var started = false
    private var lastLaunch = 0L
    private var lastRecord = 0L
    private var lastFg: String? = null
    private var seeded = false
    private val essentials: Set<String> by lazy { Essentials.defaults(this) }
    private var lastUsageOk: Boolean? = null
    private var lastOverlayOk: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.ensure(this)
        // If the service was not running for a while (force-stop, killed, phone off), leave a note in the guardian log.
        val now = System.currentTimeMillis()
        val last = Store.heartbeat
        if (last > 0 && Store.monitoringEnabled && now - last > 10 * 60_000L) {
            Store.logEvent(
                "tamper",
                "Monitoring was not running for ${fmtDurationShort(now - last)} (app stopped, killed, or phone was off)"
            )
        }
        Store.heartbeat = now
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.foreground(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!started) {
            started = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Destroyed while enforcement is meant to be on (killed by the system): ask the watchdog to bring us back.
        if (Store.monitoringEnabled) Watchdog.schedule(this, 10_000L)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents can take the service with it on some phones.
        if (Store.monitoringEnabled) Watchdog.schedule(this, 5_000L)
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                tick()
            } catch (e: Exception) {
                Log.e("DoomGuard", "tick failed", e)
            }
            delay(2000)
        }
    }

    /** Notes (and notifies about) permissions being switched off or back on while enforcement is enabled. */
    private fun watchPermissions() {
        if (!Store.monitoringEnabled) return
        val usage = Perms.hasUsage(this)
        val overlay = Perms.hasOverlay(this)
        if (lastUsageOk != null && lastUsageOk != usage) permChanged("Usage access", usage, 2010)
        if (lastOverlayOk != null && lastOverlayOk != overlay) permChanged("Display over other apps", overlay, 2011)
        lastUsageOk = usage
        lastOverlayOk = overlay
    }

    private fun permChanged(name: String, on: Boolean, id: Int) {
        Store.logEvent("tamper", "$name was turned ${if (on) "back on" else "off"}")
        if (!on) {
            Notifier.post(
                this, id, Notifier.CH_NUDGE,
                "DoomGuard: $name is off",
                "Limits can't be enforced until it is turned back on. This was added to the guardian log."
            )
        }
    }

    private fun tick() {
        val start = System.currentTimeMillis()
        if (start - Store.heartbeat > 30_000L) Store.heartbeat = start
        watchPermissions()
        if (!Store.monitoringEnabled || !Perms.hasUsage(this)) return
        val now = System.currentTimeMillis()
        val t = tracker ?: UsageTracker(this).also { tracker = it }
        t.refresh(now)

        // Did the foreground app just change? (The first tick only records the starting point.)
        val fg = t.foreground
        val changed = seeded && fg != lastFg
        lastFg = fg
        seeded = true

        maybeQuote(now)
        maybeEvening()
        Store.finishFocusIfDue(now)?.let { mins ->
            Notifier.post(
                this, 2003, Notifier.CH_QUOTE,
                "Focus session complete",
                "Nice work. $mins minutes were added to your activity log."
            )
        }

        val limits = Store.limitsToday()
        if (now - lastRecord > 60_000) {
            lastRecord = now
            WebFilter.ensure(this) // once a minute: make sure the website filter is still running
            Store.recordUsage(today(), t.snapshotAll(now) + t.snapshot(now, limits.keys))
            Store.recordUnlocks(today(), t.stats.unlocks, t.stats.firstUnlock)
            StatusWidget.refresh(this)
            AutoBackup.runIfDue(this)
        }

        if (fg == null) return
        if (fg in essentials || fg in Store.alwaysAllowed()) return // calling, messaging, maps and your own allow-list
        val limitMin = limits[fg] ?: return
        val limitMs = limitMin * 60_000L
        val used = t.usageMs(fg, now)
        val s = Store.pkgState(fg)

        // Shared cap across every limited app (0 = off).
        val capMin = Store.totalCapMin
        val capMs = capMin * 60_000L
        val totalUsed = if (capMs > 0) limits.keys.sumOf { t.usageMs(it, now) } else 0L
        val overApp = used >= limitMs
        val overTotal = capMs > 0 && totalUsed >= capMs

        var mode: String? = null
        when {
            s.guardianUntil > now -> {} // the guardian granted time with the PIN: overrides everything below
            Store.focusSessionActive() -> mode = "SESSION"
            s.lockUntil > now -> mode = "LOCKED"
            Store.focusActiveNow() -> mode = "FOCUS"
            s.snoozeUntil > now -> {} // user bought extra minutes
            overApp || overTotal -> mode = "LIMIT"
        }

        if (mode == null) {
            if (!s.warned && used >= limitMs * 0.8 && used < limitMs) {
                s.warned = true
                Store.savePkgState(fg, s)
                val q = Quotes.pick("reflection", null)
                Notifier.post(
                    this, fg.hashCode(), Notifier.CH_NUDGE,
                    "${appLabel(this, fg)}: ${fmtDuration(limitMs - used)} left today",
                    "You've used ${fmtDuration(used)} of your ${fmtMinutes(limitMin)} limit. Start wrapping up.\n\n“${q.text}” — ${q.author}"
                )
            }
            // Nothing is blocking, but the app was just opened: ask what it's for.
            if (changed && Store.openPause && s.okUntil <= now) mode = "INTENT" else return
        }

        if (mode == "LIMIT") Store.markOver(today())
        if (BlockActivity.showing || now - lastLaunch < 5000) return
        lastLaunch = now
        // If only the shared cap tripped, show the total instead of this app's own numbers.
        val total = mode == "LIMIT" && !overApp && overTotal
        launchBlock(fg, mode, if (total) totalUsed else used, if (total) capMin else limitMin, total)
    }

    private fun launchBlock(pkg: String, mode: String, used: Long, limitMin: Int, total: Boolean) {
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("pkg", pkg)
            .putExtra("mode", mode)
            .putExtra("used", used)
            .putExtra("limit", limitMin)
            .putExtra("total", total)
        if (Perms.hasOverlay(this)) {
            // With "display over other apps" granted, Android lets us start the screen from the background.
            startActivity(i)
            // The phone may silently refuse to open it over another app (Xiaomi's pop-up switch, for one). If it
            // hasn't appeared shortly after, fall back to a heads-up notification so the limit is never invisible.
            scope.launch {
                delay(2500)
                if (!BlockActivity.showing && mode != "INTENT") {
                    val pi = PendingIntent.getActivity(this@MonitorService, pkg.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifier.post(
                        this@MonitorService, pkg.hashCode(), Notifier.CH_NUDGE,
                        "Time to pause on ${appLabel(this@MonitorService, pkg)}",
                        "Tap to take a pause.", pi
                    )
                }
            }
        } else if (mode != "INTENT") {
            val pi = PendingIntent.getActivity(this, pkg.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            Notifier.post(
                this, pkg.hashCode(), Notifier.CH_NUDGE,
                "Time to pause on ${appLabel(this, pkg)}",
                "Tap to take a pause. (Grant 'Display over other apps' so this appears automatically.)", pi
            )
        }
    }

    private fun maybeQuote(now: Long) {
        val interval = Store.quoteIntervalMin
        if (interval <= 0) return
        if (Store.lastQuoteAt == 0L) { Store.lastQuoteAt = now; return }
        if (now - Store.lastQuoteAt < interval * 60_000L) return
        Store.lastQuoteAt = now
        val q = Quotes.pick(null, Store.currentQuote?.text)
        Store.currentQuote = q
        if (Store.quoteNotifs) Notifier.post(this, 2001, Notifier.CH_QUOTE, q.author, "“${q.text}”")
    }

    private fun maybeEvening() {
        if (!Store.eveningEnabled) return
        if (LocalTime.now().hour < Store.eveningHour) return
        if (Store.lastEveningDay == today()) return
        Store.lastEveningDay = today()
        Notifier.post(
            this, 2002, Notifier.CH_QUOTE,
            "Evening review",
            "Log what you did today and answer one question: what are you proud of?"
        )
    }

    companion object {
        fun start(ctx: Context) {
            Watchdog.schedule(ctx)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                Log.w("DoomGuard", "could not start service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}

/** Restarts monitoring after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.init(context)
        WebFilter.ensure(context)
        if (Store.monitoringEnabled || Store.webFilterActive()) Watchdog.schedule(context)
        if (Store.monitoringEnabled && Perms.hasUsage(context)) MonitorService.start(context)
    }
}
