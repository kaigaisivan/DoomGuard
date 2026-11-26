@file:OptIn(ExperimentalMaterial3Api::class)

package com.doomguard.app

import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    // Time per app over the last 7 days, limited or not.
    val weekTotals = HashMap<String, Long>()
    days.forEach { d ->
        val perApp = if (d == LocalDate.now()) todayUsage else history[d.toString()] ?: emptyMap()
        perApp.forEach { (p, ms) -> weekTotals[p] = (weekTotals[p] ?: 0L) + ms }
    }
    val appSlices = weekTotals.filter { (p, ms) -> p in launcherSet && ms >= 60_000L }
        .entries.sortedByDescending { it.value }
        .let { all ->
            val top = all.take(5).map { appLabel(ctx, it.key) to it.value }
            val rest = all.drop(5).sumOf { it.value }
            if (rest > 0) top + ("Other" to rest) else top
        }

    val monthAgo = System.currentTimeMillis() - 30 * DAY_MS
    val recentTriggers = triggers.filter { it.ts >= monthAgo && it.mood != "unspecified" }
    val moodCounts = recentTriggers.groupingBy { it.mood }.eachCount().entries.sortedByDescending { it.value }
    val bucketCounts = triggers.filter { it.ts >= monthAgo }.groupingBy {
        bucket(Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault()).hour)
    }.eachCount().entries.sortedByDescending { it.value }

    val weekAgo = System.currentTimeMillis() - 7 * DAY_MS
    val catTotals = acts.filter { it.ts >= weekAgo && it.minutes > 0 }
        .groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.minutes } }
        .entries.sortedByDescending { it.value }

    val pickupsToday = unlocks[LocalDate.now().toString()] ?: 0
    val pickupDays = days.map { unlocks[it.toString()] ?: 0 }.filter { it > 0 }
    val pickupAvg = if (pickupDays.isEmpty()) 0 else pickupDays.sum() / pickupDays.size

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
                    Text("${fmtMinutes(weekMin.toInt())} this week", style = MaterialTheme.typography.titleMedium)
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
                }
                if (pickupsToday > 0 || pickupAvg > 0) {
                    Text(
                        "Phone pickups: $pickupsToday today, $pickupAvg a day on average.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (appSlices.isNotEmpty()) {
            item {
                Section("Where the time went (7 days)") {
                    PieChart(appSlices) { fmtDuration(it) }
                }
            }
        }

        if (moodCounts.isNotEmpty() || bucketCounts.isNotEmpty()) {
            item {
                Section("What triggers the urge") {
                    if (recentTriggers.size >= 3 && moodCounts.isNotEmpty()) {
                        val topBucket = bucketCounts.firstOrNull()?.key
                        Text(
                            "Most urges come when you feel ${moodCounts.first().key.lowercase()}" +
                                    (if (topBucket != null) ", usually in the ${topBucket.lowercase()}." else "."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (moodCounts.isNotEmpty()) PieChart(moodCounts.map { it.key to it.value.toLong() }) { "$it" }
                }
            }
        }

        if (catTotals.isNotEmpty()) {
            item {
                Section("Your logged time (7 days)") {
                    PieChart(catTotals.map { it.key.label to it.value.toLong() }) { "$it min" }
                }
            }
        }

        item {
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
                    if (Guardian.isSet() || ev.isNotEmpty()) {
                        appendLine("Guardian overrides: ${ev.count { it.kind == "override" }}")
                        appendLine("Tamper alerts: ${ev.count { it.kind == "tamper" }}")
                    }
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                ctx.startActivity(Intent.createChooser(send, "Share weekly summary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text("Share weekly summary") }
        }
    }
}

/** A donut chart with a plain legend beside it. Slice values are summed, so pass raw amounts. */
@Composable
private fun PieChart(slices: List<Pair<String, Long>>, format: (Long) -> String) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val other = MaterialTheme.colorScheme.outline
    val palette = listOf(primary, secondary, Color(0xFFD98C7A), Color(0xFF7A9CC6), Color(0xFFB59BC9), Color(0xFFC9B458))
    fun colorAt(i: Int, label: String): Color = if (label == "Other") other else palette[i % palette.size]

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(120.dp)) {
            val stroke = 22.dp.toPx()
            val d = size.minDimension - stroke
            var start = -90f
            slices.forEachIndexed { i, (label, v) ->
                val sweep = 360f * v / total
                drawArc(
                    color = colorAt(i, label),
                    startAngle = start,
                    sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(d, d),
                    style = Stroke(stroke)
                )
                start += sweep
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(colorAt(i, label), CircleShape))
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Text(format(v), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}