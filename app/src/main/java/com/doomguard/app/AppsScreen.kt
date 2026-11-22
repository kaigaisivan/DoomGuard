@file:OptIn(ExperimentalMaterial3Api::class)

package com.doomguard.app

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppInfo(val pkg: String, val label: String)

private val SUGGESTED = setOf(
    "com.instagram.android",
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
    "com.google.android.youtube",
    "com.twitter.android",
    "com.facebook.katana",
    "com.snapchat.android",
    "com.reddit.frontpage",
    "com.facebook.orca",
    "com.pinterest",
    "com.linkedin.android"
)

@Composable
fun AppsScreen(pad: PaddingValues) {
    val ctx = LocalContext.current
    val limits = rememberStore { Store.limits() }
    val gate = rememberGuardianGate()
    val weekend = rememberStore { Store.weekendLimits() }
    val allowed = rememberStore { Store.alwaysAllowed() }
    val essentialApps = remember { Essentials.defaults(ctx) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .filter { it.pkg != ctx.packageName }
        }
    }

    val shown = remember(apps, limits, query) {
        apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<AppInfo>({ it.pkg !in limits }, { it.pkg !in SUGGESTED }, { it.label.lowercase() })
            )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Switch on the apps you want limited and set a daily time. " +
                        "In strict mode you can lower limits and add apps, but not raise limits or remove apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(shown, key = { it.pkg }) { app ->
            val limit = limits[app.pkg]
            val builtInAllowed = app.pkg in essentialApps
            val userAllowed = app.pkg in allowed
            val isProtected = builtInAllowed || userAllowed
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val icon = remember(app.pkg) {
                            try { ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
                        }
                        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            if (isProtected && limit == null) {
                                Text("Always allowed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            } else if (app.pkg in SUGGESTED && limit == null) {
                                Text("Common time sink", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Switch(
                            enabled = limit != null || !isProtected,
                            checked = limit != null,
                            onCheckedChange = { on ->
                                if (on) Store.setLimit(app.pkg, 30)
                                else if (Store.strictActive()) {
                                    Toast.makeText(ctx, "Strict mode is on. You can't remove apps right now.", Toast.LENGTH_SHORT).show()
                                } else {
                                    gate.run("Stop limiting ${app.label}") {
                                        if (Store.removeLimit(app.pkg) && Guardian.isSet()) {
                                            Store.logEvent("change", "Removed the limit on ${app.label}")
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (limit != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { Store.setLimit(app.pkg, limit - 5) }) { Text("− 5") }
                            Text("${fmtMinutes(limit)} per day", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                if (Store.strictActive()) {
                                    Toast.makeText(ctx, "Strict mode is on. You can't raise limits right now.", Toast.LENGTH_SHORT).show()
                                } else {
                                    gate.run("Raise the limit for ${app.label}") {
                                        if (Store.setLimit(app.pkg, limit + 5) && Guardian.isSet()) {
                                            Store.logEvent("change", "Raised the limit on ${app.label} to ${fmtMinutes(limit + 5)}")
                                        }
                                    }
                                }
                            }) { Text("+ 5") }
                        }
                        val we = weekend[app.pkg]
                        if (we == null) {
                            TextButton(onClick = { Store.setWeekendLimit(app.pkg, limit) }) { Text("Use a different limit on weekends") }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { Store.setWeekendLimit(app.pkg, we - 5) }) { Text("− 5") }
                                Text("Weekends: ${fmtMinutes(we)}", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = {
                                    if (Store.strictActive()) {
                                        Toast.makeText(ctx, "Strict mode is on. You can't raise limits right now.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        gate.run("Raise the weekend limit for ${app.label}") {
                                            if (Store.setWeekendLimit(app.pkg, we + 5) && Guardian.isSet()) {
                                                Store.logEvent("change", "Raised the weekend limit on ${app.label} to ${fmtMinutes(we + 5)}")
                                            }
                                        }
                                    }
                                }) { Text("+ 5") }
                            }
                            TextButton(onClick = {
                                if (Store.strictActive()) {
                                    Toast.makeText(ctx, "Strict mode is on. You can't raise limits right now.", Toast.LENGTH_SHORT).show()
                                } else {
                                    gate.run("Use the weekday limit on weekends for ${app.label}") { Store.clearWeekendLimit(app.pkg) }
                                }
                            }) { Text("Same as weekdays") }
                        }
                    }
                    if (limit == null && !builtInAllowed) {
                        TextButton(onClick = {
                            if (!Store.toggleAlwaysAllowed(app.pkg)) {
                                Toast.makeText(ctx, "Remove ${app.label}'s limit first.", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(if (userAllowed) "Undo always allowed" else "Always allow this app") }
                    }
                }
            }
        }
    }
}
