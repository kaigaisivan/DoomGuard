package com.doomguard.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api

/** Start, watch and end a focus session. While one runs, every limited app is blocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusSessionCard() {
    val ctx = LocalContext.current
    val gate = rememberGuardianGate()
    val version = Store.version
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            Store.finishFocusIfDue(now)
            delay(1000)
        }
    }
    val end = remember(version, now) { Store.focusSessionEnd }
    var minutes by remember { mutableIntStateOf(45) }
    var label by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(Category.WORK) }

    Section("Focus session") {
        if (end > now) {
            val secs = ((end - now) / 1000).coerceAtLeast(0)
            Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displaySmall)
            val what = Store.focusSessionLabel
            if (what.isNotBlank()) Text(what, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Your limited apps are blocked until the timer ends. The session is logged automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    if (Store.strictActive()) {
                        Toast.makeText(ctx, "Strict mode is on. You can't end a session early.", Toast.LENGTH_SHORT).show()
                    } else {
                        gate.run("End the focus session early") {
                            if (Store.stopFocusSession() && Guardian.isSet()) Store.logEvent("change", "Focus session ended early")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("End early") }
        } else {
            Text(
                "Block your limited apps for a set time. It counts as productive time in your daily review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 60, 90).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("${m}m") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Category.WORK, Category.STUDY, Category.EXERCISE).forEach { c ->
                    FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.label) })
                }
            }
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("What will you work on? (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { Store.startFocusSession(minutes, label.trim(), cat); label = "" },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start $minutes-minute session") }
            if (Store.limits().isEmpty()) {
                Text(
                    "Tip: choose apps in the Apps tab first, so there is something to block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
