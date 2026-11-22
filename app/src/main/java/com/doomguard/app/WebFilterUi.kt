package com.doomguard.app

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN_FMT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** Settings section for the website filter (adult sites blocked system-wide through a local DNS VPN). */
@Composable
fun WebFilterSection(gate: GuardianGate) {
    val ctx = LocalContext.current
    val v = Store.version
    val active = remember(v) { Store.webFilterActive() }
    val offAt = remember(v) { Store.webFilterOffAt }
    val custom = remember(v) { Store.customDomains().entries.sortedBy { it.key } }
    val history = remember(v) { Store.blockedHistory() }
    val todayCount = history[today()] ?: 0
    val weekCount = (0..6).sumOf { history[LocalDate.now().minusDays(it.toLong()).toString()] ?: 0 }

    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }
    val running = remember(permTick, v) { WebFilterService.running }
    val strictDns = remember(permTick) { WebFilter.privateDnsStrict(ctx) }

    var newDomain by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun begin() {
        Store.enableWebFilter()
        WebFilter.start(ctx)
        Watchdog.schedule(ctx)
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) begin() else note = "The filter needs the VPN permission to work."
    }

    fun turnOn() {
        note = null
        val consent = VpnService.prepare(ctx)
        if (consent == null) begin() else vpnLauncher.launch(consent)
    }

    Section("Website filter") {
        Text(
            "Blocks adult sites in every browser and app. It uses a private VPN on your phone that only " +
                "answers site-name lookups: your browsing doesn't go anywhere else.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!active) {
            Button(onClick = { turnOn() }, modifier = Modifier.fillMaxWidth()) { Text("Turn on the website filter") }
        } else {
            Text(
                if (running) "Filter is ON and running." else "Filter is ON but not running right now.",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            if (!running) {
                OutlinedButton(onClick = { turnOn() }, modifier = Modifier.fillMaxWidth()) { Text("Restart the filter") }
            }
            if (offAt == 0L) {
                OutlinedButton(
                    onClick = { gate.run("Request switching off the website filter") { Store.requestWebFilterOff() } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Request switch-off (24h wait)") }
            } else {
                val whenOff = Instant.ofEpochMilli(offAt).atZone(ZoneId.systemDefault()).format(WHEN_FMT)
                Text("Switching off at $whenOff.", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { Store.cancelWebFilterOff() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep the filter on")
                }
            }
            if (strictDns) {
                Text(
                    "Private DNS is set to a specific server, which can bypass the filter. In Android Settings > Network > " +
                        "Private DNS choose Automatic or Off.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
            }
            Text("Blocked attempts: $todayCount today, $weekCount in the last 7 days", style = MaterialTheme.typography.bodySmall)
        }

        Text("Your own list", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newDomain,
                onValueChange = { newDomain = it; note = null },
                placeholder = { Text("example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (Store.addCustomDomain(newDomain)) newDomain = "" else note = "Enter a site name like example.com"
            }) { Text("Block") }
        }
        if (note != null) Text(note ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        custom.forEach { (domain, removeAt) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(domain)
                    if (removeAt > 0) {
                        Text(
                            "Unblocks " + Instant.ofEpochMilli(removeAt).atZone(ZoneId.systemDefault()).format(WHEN_FMT),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (removeAt > 0) {
                    TextButton(onClick = { Store.cancelRemoveDomain(domain) }) { Text("Keep blocked") }
                } else {
                    TextButton(onClick = {
                        gate.run("Request unblocking $domain (takes 24 hours)") { Store.requestRemoveDomain(domain) }
                    }) { Text("Unblock…") }
                }
            }
        }
        Text(
            "Sites in the built-in lists can't be removed. Sites you add can be removed only after a 24-hour wait" +
                (if (Guardian.isSet()) " and the guardian PIN" else "") + ".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Extra lock: in Android Settings > Network > VPN, open DoomGuard's settings and turn on Always-on VPN. " +
                "Leave 'Block connections without VPN' off.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
