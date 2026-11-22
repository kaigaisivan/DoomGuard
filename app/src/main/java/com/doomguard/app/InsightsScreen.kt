@file:OptIn(ExperimentalMaterial3Api::class)

package com.doomguard.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private fun bucket(hour: Int): String = when (hour) {
    in 5..11 -> "Morning"
    in 12..16 -> "Afternoon"
    in 17..21 -> "Evening"
    else -> "Late night"
}

@Composable
fun InsightsScreen(pad: PaddingValues) {
    val ctx = LocalContext.current
    val limits = rememberStore { Store.limits() }
    val history = rememberStore { Store.usageHistory() }
    val triggers = rememberStore { Store.triggers() }
    val acts = rememberStore { Store.activities() }
    val unlocks = rememberStore { Store.unlockHistory() }

    var todayUsage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var launcherSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        launcherSet = withContext(Dispatchers.IO) { launcherPackages(ctx) }
        if (Perms.hasUsage(ctx)) {
            todayUsage = withContext(Dispatchers.IO) {
                UsageTracker.computeTotals(ctx, midnight(), System.currentTimeMillis())
            }
        }
    }

    val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    val perDay = days.map { d ->
        val ms = if (d == LocalDate.now()) limits.keys.sumOf { todayUsage[it] ?: 0L }
        else limits.keys.sumOf { history[d.toString()]?.get(it) ?: 0L }
        d to ms
    }
    val weekMin = perDay.sumOf { it.second } / 60_000L
    val maxMs = (perDay.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(60_000L)

    // Time per app over the last 7 days, limited or not (days before this update only have limited apps).
    val weekTotals = HashMap<String, Long>()
    days.forEach { d ->
        val perApp = if (d == LocalDate.now()) todayUsage else history[d.toString()] ?: emptyMap()
        perApp.forEach { (p, ms) -> weekTotals[p] = (weekTotals[p] ?: 0L) + ms }
    }
    val topApps = weekTotals.filter { (p, ms) -> p in launcherSet && ms >= 60_000L }
        .entries.sortedByDescending { it.value }.take(10)

    val monthAgo = System.currentTimeMillis() - 30 * DAY_MS
    val openedTo = "Opened to: "
    val recentTriggers = triggers.filter { it.ts >= monthAgo && it.mood != "unspecified" && !it.mood.startsWith(openedTo) }
    val intentCounts = triggers.filter { it.ts >= monthAgo && it.mood.startsWith(openedTo) }
        .groupingBy { it.mood.removePrefix(openedTo) }.eachCount().entries.sortedByDescending { it.value }
    val moodCounts = recentTriggers.groupingBy { it.mood }.eachCount().entries.sortedByDescending { it.value }
    val bucketCounts = triggers.filter { it.ts >= monthAgo }.groupingBy {
        bucket(Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault()).hour)
    }.eachCount().entries.sortedByDescending { it.value }

    val weekAgo = System.currentTimeMillis() - 7 * DAY_MS
    val catTotals = acts.filter { it.ts >= weekAgo && it.minutes > 0 }
        .groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.minutes } }
        .entries.sortedByDescending { it.value }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Section("Last 7 days in limited apps") {
                if (limits.isEmpty()) {
                    Text("Pick apps in the Apps tab and this chart fills in as you go.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        perDay.forEach { (d, ms) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Text(if (ms > 0) fmtDuration(ms) else "", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height((100f * ms / maxMs).coerceAtLeast(2f).dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                )
                                Text(
                                    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text("History builds from the day you installed the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (limits.isNotEmpty() && weekMin > 0) {
            item {
                Section("Reality check") {
                    Text("${fmtMinutes(weekMin.toInt())} in these apps this week.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "That's roughly ${weekMin} pages of a book at a page a minute, " +
                            "or ${weekMin / 30} thirty-minute walks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (topApps.isNotEmpty()) {
            item {
                Section("Top apps (7 days)") {
                    BarList(null, topApps.map { appLabel(ctx, it.key) to (it.value / 60_000L).toInt() }, suffix = " min")
                    Text(
                        "Apps you haven't limited are recorded from the day you installed this update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (unlocks.values.any { it > 0 }) {
            item {
                Section("Phone pickups (7 days)") {
                    BarList(
                        null,
                        days.map { d ->
                            d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to (unlocks[d.toString()] ?: 0)
                        }
                    )
                }
            }
        }

        item {
            Section("What triggers the urge") {
                if (triggers.isEmpty()) {
                    Text(
                        "When the pause screen appears, tap how you were feeling. After a few weeks, patterns show up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (moodCounts.size >= 1 && recentTriggers.size >= 3) {
                        val topBucket = bucketCounts.firstOrNull()?.key
                        Text(
                            "Most urges come when you feel ${moodCounts.first().key.lowercase()}" +
                                (if (topBucket != null) ", usually in the ${topBucket.lowercase()}." else "."),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    if (moodCounts.isNotEmpty()) BarList("Feeling", moodCounts.map { it.key to it.value })
                    if (intentCounts.isNotEmpty()) BarList("Why you opened apps", intentCounts.map { it.key to it.value })
                    BarList("Time of day", bucketCounts.map { it.key to it.value })
                }
            }
        }

        item {
            Section("Where your logged time went (7 days)") {
                if (catTotals.isEmpty()) {
                    Text("Log activities with minutes to see this.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    BarList(null, catTotals.map { it.key.label to it.value }, suffix = " min")
                }
            }
        }

        item {
            Section("Accountability") {
                Text("Share a plain-text weekly summary with a friend.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    val body = buildString {
                        appendLine("My DoomGuard week")
                        appendLine("Time in limited apps: ${fmtMinutes(weekMin.toInt())}")
                        perDay.forEach { (d, ms) ->
                            appendLine("  ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}: ${fmtDuration(ms)}")
                        }
                        val s = Store.streak()
                        appendLine("Streak: ${s.first} days (best ${s.second})")
                        catTotals.forEach { appendLine("${it.key.label}: ${it.value} min logged") }
                        if (Store.webFilterActive()) {
                            val blockedHistory = Store.blockedHistory()
                            val blockedWeek = (0..6).sumOf { blockedHistory[LocalDate.now().minusDays(it.toLong()).toString()] ?: 0 }
                            appendLine("Blocked site attempts: $blockedWeek")
                        }
                        val ev = Store.events().filter { it.ts >= weekAgo }
                        if (Guardian.isSet() || ev.isNotEmpty() || Store.emergencyPerDay > 0) {
                            appendLine("Guardian overrides: ${ev.count { it.kind == "override" }}")
                            appendLine("Tamper alerts: ${ev.count { it.kind == "tamper" }}")
                            appendLine("Emergency exits: ${ev.count { it.kind == "emergency" }}")
                        }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    ctx.startActivity(Intent.createChooser(send, "Share weekly summary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share weekly summary") }
            }
        }
    }
}

@Composable
private fun BarList(title: String?, rows: List<Pair<String, Int>>, suffix: String = "") {
    val max = (rows.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        rows.forEach { (label, n) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text("$n$suffix", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { n.toFloat() / max }, modifier = Modifier.fillMaxWidth())
        }
    }
}
