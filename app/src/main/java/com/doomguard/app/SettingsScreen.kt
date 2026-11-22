@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.doomguard.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId

private fun hourLabel(h: Int) = "%02d:00".format(h)

@Composable
private fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(hour - 1) }) { Text("−") }
            Text(hourLabel(hour), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onChange(hour + 1) }) { Text("+") }
        }
    }
}

@Composable
private fun PermRow(name: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$name: " + if (granted) "on" else "off", color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        if (!granted) OutlinedButton(onClick = onGrant) { Text("Fix") }
    }
}

@Composable
fun SettingsScreen(pad: PaddingValues) {
    val ctx = LocalContext.current
    val gate = rememberGuardianGate()
    // Re-read everything whenever a stored value changes.
    val v = Store.version
    val strict = remember(v) { Store.strictActive() }
    val offAt = remember(v) { Store.strictDisableAt }
    // Captured as plain locals so the lazy list below is rebuilt when any of them change.
    val themeMode = remember(v) { Store.themeMode }
    val openPause = remember(v) { Store.openPause }
    val openSec = remember(v) { Store.openPauseSeconds }
    val monitoring = remember(v) { Store.monitoringEnabled }
    val quoteInterval = remember(v) { Store.quoteIntervalMin }
    val quoteNotifs = remember(v) { Store.quoteNotifs }
    val focusOn = remember(v) { Store.focusEnabled }
    val focusStart = remember(v) { Store.focusStart }
    val focusEnd = remember(v) { Store.focusEnd }
    val eveOn = remember(v) { Store.eveningEnabled }
    val eveHour = remember(v) { Store.eveningHour }
    val totalCap = remember(v) { Store.totalCapMin }
    val emergencyMax = remember(v) { Store.emergencyPerDay }

    // Starting or lowering a cap is free. Raising or removing it is loosening, so strict mode refuses and the PIN is asked.
    fun changeCap(new: Int) {
        val cur = Store.totalCapMin
        val loosening = cur > 0 && (new <= 0 || new > cur)
        when {
            loosening && Store.strictActive() ->
                Toast.makeText(ctx, "Strict mode is on. You can't raise or remove the cap right now.", Toast.LENGTH_SHORT).show()
            loosening -> gate.run("Raise or remove the daily total cap") { Store.setTotalCap(new) }
            else -> Store.setTotalCap(new)
        }
    }

    fun changeEmergency(new: Int) {
        val loosening = new > Store.emergencyPerDay
        when {
            loosening && Store.strictActive() ->
                Toast.makeText(ctx, "Strict mode is on. You can't allow more emergency exits right now.", Toast.LENGTH_SHORT).show()
            loosening -> gate.run("Allow more emergency exits per day") { Store.setEmergencyPerDay(new) }
            else -> Store.setEmergencyPerDay(new)
        }
    }

    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }
    val usageOk = remember(permTick) { Perms.hasUsage(ctx) }
    val overlayOk = remember(permTick) { Perms.hasOverlay(ctx) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val saved = try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportAll().toByteArray()) }
                true
            } catch (e: Exception) {
                false
            }
            Toast.makeText(ctx, if (saved) "Backup saved" else "Could not save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Section("Permissions") {
                PermRow("Usage access", usageOk) { Perms.openUsage(ctx) }
                PermRow("Display over other apps", overlayOk) { Perms.openOverlay(ctx) }
                val miui = remember(permTick) { Perms.miuiPopupAllowed(ctx) }
                if (miui != null) {
                    PermRow("Pop-up windows in background (Xiaomi)", miui) { Perms.openMiuiPermissions(ctx) }
                    if (!miui) Text(
                        "This is why the pause screen may not appear. In the page that opens, turn on " +
                            "\"Display pop-up windows while running in the background\" and \"Show on lock screen\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = {
                        Toast.makeText(ctx, "Switch to another app now. The pause screen should appear in 10 seconds.", Toast.LENGTH_LONG).show()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                ctx.startActivity(
                                    Intent(ctx, BlockActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        .putExtra("pkg", "com.android.settings").putExtra("mode", "INTENT")
                                        .putExtra("used", 0L).putExtra("limit", 0).putExtra("total", false)
                                )
                            } catch (e: Exception) { /* blocked by the system */ }
                        }, 10_000L)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test the pause screen") }
                val heartbeat = remember(permTick, v) { Store.heartbeat }
                val ago = System.currentTimeMillis() - heartbeat
                Text(
                    when {
                        !monitoring -> "Monitor: paused"
                        heartbeat == 0L -> "Monitor: starting up"
                        ago < 120_000L -> "Monitor: running"
                        else -> "Monitor: last seen ${fmtDurationShort(ago)} ago. It restarts itself about every 15 minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (monitoring && heartbeat != 0L && ago >= 120_000L) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { Perms.openBattery(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Exempt from battery optimisation")
                }
                Text(
                    "Some phones (Tecno, Infinix, Xiaomi, Samsung, Oppo) also need DoomGuard set to 'unrestricted' " +
                        "or 'auto-start' in the system app settings so the monitor isn't killed in the background.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Section("Appearance") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Match phone", 1 to "Light", 2 to "Dark").forEach { (m, label) ->
                        FilterChip(selected = themeMode == m, onClick = { Store.themeMode = m }, label = { Text(label) })
                    }
                }
            }
        }

        item {
            Section("Monitoring") {
                ToggleRow(
                    "Enforce limits",
                    if (strict) "Locked while strict mode is on" else "Turn off to pause all blocking",
                    checked = monitoring,
                    enabled = !strict
                ) {
                    if (it) {
                        Store.monitoringEnabled = true
                        MonitorService.start(ctx)
                    } else {
                        gate.run("Pause all blocking") {
                            Store.monitoringEnabled = false
                            if (!Store.webFilterActive()) Watchdog.cancel(ctx) // the filter still needs its watchdog
                            MonitorService.stop(ctx)
                        }
                    }
                }
            }
        }

        item {
            Section("Open-app pause") {
                ToggleRow(
                    "Ask what you're here for",
                    "Shown when you open a limited app, before it loads",
                    checked = openPause
                ) { on ->
                    if (on) Store.openPause = true
                    else gate.run("Turn off the open-app pause") { Store.openPause = false }
                }
                if (openPause) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Wait before opening")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                gate.run("Shorten the open-app wait") { Store.openPauseSeconds = openSec - 1 }
                            }) { Text("−") }
                            Text("$openSec s", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { Store.openPauseSeconds = openSec + 1 }) { Text("+") }
                        }
                    }
                    Text(
                        "After you answer, the app stays open for 10 minutes without asking again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Section("Strict mode") {
                Text(
                    "No 'continue' button, limits can't be raised, apps can't be removed, and monitoring can't be paused. " +
                        "Turning it off takes 24 hours, so a weak moment can't undo it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!strict) {
                    Button(onClick = { Store.enableStrict() }, modifier = Modifier.fillMaxWidth()) { Text("Turn on strict mode") }
                } else if (offAt > 0) {
                    val whenOff = Instant.ofEpochMilli(offAt).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
                    Text("Switching off at $whenOff.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { Store.cancelStrictOff() }, modifier = Modifier.fillMaxWidth()) { Text("Keep strict mode on") }
                } else {
                    Text("Strict mode is ON.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(
                        onClick = { gate.run("Request switching off strict mode") { Store.requestStrictOff() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request switch-off (24h delay)")
                    }
                }
            }
        }

        item {
            Section("Daily total cap") {
                Text(
                    "One shared limit across all your limited apps, so swapping one feed for another doesn't help.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToggleRow("Cap total time", checked = totalCap > 0) { on -> changeCap(if (on) 120 else 0) }
                if (totalCap > 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Per day")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { changeCap(maxOf(15, totalCap - 15)) }) { Text("−") }
                            Text(fmtMinutes(totalCap), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { changeCap(totalCap + 15) }) { Text("+") }
                        }
                    }
                }
            }
        }

        item {
            Section("Emergency exit") {
                Text(
                    "A way out so blocking never traps you. On the pause screen you can take 10 minutes for a real reason. " +
                        "It works even in strict mode, and every use is written to the guardian log.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allowed per day")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { changeEmergency(emergencyMax - 1) }) { Text("−") }
                        Text(if (emergencyMax == 0) "Off" else "$emergencyMax", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { changeEmergency(emergencyMax + 1) }) { Text("+") }
                    }
                }
            }
        }

        item {
            Section("Quotes") {
                Text("Show a new quote every", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours").forEach { (m, label) ->
                        FilterChip(selected = quoteInterval == m, onClick = { Store.quoteIntervalMin = m }, label = { Text(label) })
                    }
                }
                ToggleRow("Also send as a notification", checked = quoteNotifs) { Store.quoteNotifs = it }
            }
        }

        item {
            Section("Focus hours") {
                ToggleRow("Block limited apps during these hours", checked = focusOn) { on ->
                    if (on) Store.focusEnabled = true
                    else gate.run("Turn off focus hours") { Store.focusEnabled = false }
                }
                if (focusOn) {
                    HourStepper("From", focusStart) { h -> gate.run("Change focus hours") { Store.focusStart = h } }
                    HourStepper("Until", focusEnd) { h -> gate.run("Change focus hours") { Store.focusEnd = h } }
                }
            }
        }

        item {
            Section("Evening review") {
                ToggleRow("Remind me to log my day", checked = eveOn) { Store.eveningEnabled = it }
                if (eveOn) HourStepper("Remind at", eveHour) { Store.eveningHour = it }
            }
        }

        item { WebFilterSection(gate) }

        item { AccountabilitySection(gate) }

        item { AutoBackupSection() }

        item {
            Section("Backup and restore") {
                Text(
                    "Save everything (log, limits, streaks, settings, favourites) to one file, or load it back on this or another phone. Restoring is blocked while strict mode is on and needs the guardian PIN if one is set. The PIN itself is never saved in the file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { exportLauncher.launch("doomguard-backup-${today()}.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save backup file")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from backup file")
                }
            }
        }

        item {
            Section("Your data") {
                Text("Everything is stored on this phone only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, Store.exportCsv())
                        .putExtra(Intent.EXTRA_SUBJECT, "DoomGuard activity log")
                    ctx.startActivity(Intent.createChooser(send, "Export activity log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Text("Export activity log (CSV)") }
            }
        }
    }

    val importUri = pendingImport
    if (importUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore backup?") },
            text = { Text("This replaces everything currently in DoomGuard (log, limits, settings) with the contents of the backup file.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    gate.run("Restore a backup") {
                        val text = try {
                            ctx.contentResolver.openInputStream(importUri)?.bufferedReader()?.use { it.readText() }
                        } catch (e: Exception) {
                            null
                        }
                        val ok = text != null && Store.importAll(text)
                        Toast.makeText(
                            ctx,
                            if (ok) "Backup restored" else "Could not restore (invalid file, strict mode, or locked PIN)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }
        )
    }
}
