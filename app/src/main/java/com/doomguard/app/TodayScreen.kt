@file:OptIn(ExperimentalMaterial3Api::class)

package com.doomguard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(pad: PaddingValues, goTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    val gate = rememberGuardianGate()
    val limits = rememberStore { Store.limitsToday() }
    val totalCap = rememberStore { Store.totalCapMin }
    val webOn = rememberStore { Store.webFilterActive() }
    val tasks = rememberStore { Store.tasksToday() }
    val acts = rememberStore { Store.activities().filter { it.day == today() } }
    val streak = rememberStore { Store.streak() }
    val updateVersion = rememberStore { UpdateChecker.available(ctx) }
    val reflection = rememberStore { Store.reflection(today()) }

    var usage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var unlocks by remember { mutableIntStateOf(0) }
    var firstUnlock by remember { mutableStateOf(0L) }
    var launcherSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAllApps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { launcherSet = withContext(Dispatchers.IO) { launcherPackages(ctx) } }
    // Every app used today (not just limited ones), longest first. Apps under 10 seconds are ignored.
    val allApps = remember(usage, launcherSet) {
        usage.filter { (p, ms) -> p in launcherSet && ms >= 10_000L }.entries.sortedByDescending { it.value }
    }
    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }
    val usageOk = remember(permTick) { Perms.hasUsage(ctx) }
    val overlayOk = remember(permTick) { Perms.hasOverlay(ctx) }

    LaunchedEffect(usageOk) {
        while (usageOk) {
            val d = withContext(Dispatchers.IO) {
                UsageTracker.computeDay(ctx, midnight(), System.currentTimeMillis())
            }
            usage = d.totals
            unlocks = d.unlocks
            firstUnlock = d.firstUnlock
            delay(15_000)
        }
    }

    var newTask by remember { mutableStateOf("") }
    var showReflect by remember { mutableStateOf(false) }
    var draft by remember(reflection) { mutableStateOf(reflection) }

    val scrollMin = limits.keys.sumOf { usage[it] ?: 0L } / 60_000L
    val productiveMin = acts.filter { it.cat.productive }.sumOf { it.minutes }.toLong()
    val isEvening = LocalTime.now().hour >= Store.eveningHour

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (!usageOk || !overlayOk) {
            item {
                Section("Finish setup") {
                    Text(
                        "DoomGuard needs two permissions to work: Usage access (to see which app is open) " +
                                "and Display over other apps (to show the pause screen)."
                    )
                    if (!usageOk) Button(onClick = { Perms.openUsage(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Grant usage access") }
                    if (!overlayOk) Button(onClick = { Perms.openOverlay(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Allow display over other apps") }
                }
            }
        }

        item {
            Section("Streak") {
                if (limits.isEmpty()) {
                    Text("Choose apps to limit and your streak of days under the limit starts counting.")
                    OutlinedButton(onClick = { goTo(TAB_APPS) }) { Text("Choose apps") }
                } else {
                    Text("${streak.first} day streak · best ${streak.second}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A slip resets the current streak only. Nothing else is lost.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (usageOk) {
            item {
                Section("All apps today", modifier = Modifier.clickable { goTo(TAB_APPS) }) {
                    // The shared daily cap and weekend note used to live in a separate "Screen time today" card.
                    if (limits.isNotEmpty() && totalCap > 0) {
                        val totalUsed = limits.keys.sumOf { usage[it] ?: 0L }
                        val totalFrac = (totalUsed.toFloat() / (totalCap * 60_000f)).coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Daily total cap", style = MaterialTheme.typography.titleSmall)
                            Text("${fmtDuration(totalUsed)} / ${fmtMinutes(totalCap)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(
                            progress = { totalFrac },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (totalFrac >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (limits.isNotEmpty() && Store.isWeekend() && Store.weekendLimits().isNotEmpty()) {
                        Text("Weekend limits are in effect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (allApps.isEmpty()) {
                        Text(
                            "Nothing recorded yet. Every app you use will appear here with its time.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val top = allApps.first().value.coerceAtLeast(1L)
                        Text(
                            "${fmtDurationShort(allApps.sumOf { it.value })} across ${allApps.size} apps",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Tap the card to manage every app in the Apps tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val shown = if (showAllApps) allApps else allApps.take(8)
                        shown.forEach { (pkg, ms) ->
                            AppUsageRow(pkg, ms, ms.toFloat() / top, limits[pkg], gate)
                        }
                        if (allApps.size > 8) {
                            TextButton(onClick = { showAllApps = !showAllApps }) {
                                Text(if (showAllApps) "Show fewer" else "Show all ${allApps.size} apps")
                            }
                        }
                    }
                }
            }
        }

        if (updateVersion != null) {
            item { UpdateBanner(updateVersion) }
        }

        item { FocusSessionCard() }

        if (webOn) {
            item {
                Section("Website filter") {
                    val blocked = Store.blockedHistory()[today()] ?: 0
                    if (WebFilterService.running) {
                        Text("On. $blocked blocked ${if (blocked == 1) "attempt" else "attempts"} today.", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("On, but not running right now.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { goTo(TAB_SETTINGS) }) { Text("Open Settings to restart it") }
                    }
                }
            }
        }

        if (usageOk) {
            item {
                Section("Phone pickups") {
                    val first = if (firstUnlock > 0) {
                        " · first at " + Instant.ofEpochMilli(firstUnlock).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                    } else ""
                    Text("$unlocks unlocks today$first", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Fewer, more deliberate pickups mean fewer chances to get pulled in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Section("Today's intentions") {
                tasks.forEach { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t.done, onCheckedChange = { Store.toggleTask(t.id) })
                        Text(
                            t.text,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = { Store.deleteTask(t.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTask, onValueChange = { newTask = it },
                        placeholder = { Text("What will you do today?") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (newTask.isNotBlank()) { Store.addTask(newTask.trim()); newTask = "" }
                    }) { Text("Add") }
                }
            }
        }

        item {
            Section(if (isEvening) "Evening review" else "Daily review") {
                Text("Logged productive time: ${fmtMinutes(productiveMin.toInt())}")
                if (limits.isNotEmpty()) Text("Time in limited apps: ${fmtMinutes(scrollMin.toInt())}")
                if (limits.isNotEmpty() && (productiveMin > 0 || scrollMin > 0)) {
                    Text(
                        if (productiveMin >= scrollMin) "You're putting more into things you chose than into the feed. Keep going."
                        else "The feed is winning today. There's still time to log something real.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (reflection.isNotBlank()) Text("Proud of: $reflection", fontFamily = QuoteFont)
                OutlinedButton(onClick = { showReflect = true }) {
                    Text(if (reflection.isBlank()) "What are you proud of today?" else "Edit reflection")
                }
            }
        }
    }

    if (showReflect) {
        AlertDialog(
            onDismissRequest = { showReflect = false },
            title = { Text("What are you proud of today?") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { Store.saveReflection(today(), draft.trim()); showReflect = false }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (reflection.isNotBlank()) {
                        TextButton(onClick = {
                            Store.saveReflection(today(), "")
                            draft = ""
                            showReflect = false
                        }) { Text("Delete") }
                    }
                    TextButton(onClick = { showReflect = false }) { Text("Cancel") }
                }
            }
        )
    }
}