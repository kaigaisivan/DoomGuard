package com.doomguard.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: swipe down, tap, and a focus session starts. While one runs the tile shows the time left.
 * Tapping a running session tries to end it early; strict mode or a locked guardian PIN refuse that and open the app instead.
 */
class FocusTileService : TileService() {
    companion object {
        const val TILE_MINUTES = 25
    }

    override fun onStartListening() {
        Store.init(applicationContext)
        Store.finishFocusIfDue()
        refreshTile()
    }

    override fun onClick() {
        Store.init(applicationContext)
        if (Store.focusSessionActive()) {
            val stopped = !Store.strictActive() && Store.stopFocusSession()
            if (!stopped) {
                openApp()
                return
            }
            if (Guardian.isSet()) Store.logEvent("change", "Focus session ended early")
        } else {
            Store.startFocusSession(TILE_MINUTES, "", Category.WORK)
        }
        StatusWidget.refresh(this)
        refreshTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }

    private fun refreshTile() {
        val t = qsTile ?: return
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        if (Store.focusSessionActive()) {
            val mins = ((Store.focusSessionEnd - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            t.state = Tile.STATE_ACTIVE
            t.label = "Focus: $mins min left"
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = "Tap to end"
        } else {
            t.state = Tile.STATE_INACTIVE
            t.label = "Start focus"
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = "$TILE_MINUTES min"
        }
        t.updateTile()
    }
}
