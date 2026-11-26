package com.doomguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Human-readable message for a failed PIN check, or null if the PIN was fine. */
fun pinErrorText(r: PinResult): String? = when (r) {
    is PinResult.Ok -> null
    is PinResult.NotSet -> "No guardian PIN is set."
    is PinResult.Wrong -> "Wrong PIN. ${r.triesLeft} ${if (r.triesLeft == 1) "try" else "tries"} left."
    is PinResult.Locked -> {
        val mins = ((r.untilMs - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
        "Too many wrong tries. Try again in $mins min."
    }
}

class GatePending(val reason: String, val action: () -> Unit)

/**
 * Runs an action that loosens the rules. With no PIN set (or inside the unlock window) it runs straight away,
 * otherwise it asks for the guardian PIN first.
 */
class GuardianGate {
    var pending by mutableStateOf<GatePending?>(null)

    fun run(reason: String, action: () -> Unit) {
        if (!Guardian.locked()) action() else pending = GatePending(reason, action)
    }
}

@Composable
fun rememberGuardianGate(): GuardianGate {
    val gate = remember { GuardianGate() }
    val p = gate.pending
    if (p != null) {
        PinDialog(
            message = p.reason,
            onDismiss = { gate.pending = null },
            onSuccess = { gate.pending = null; p.action() }
        )
    }
    return gate
}

@Composable
fun PinDialog(message: String, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardian PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = null },
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = Guardian.verify(pin)
                if (r is PinResult.Ok) onSuccess() else { error = pinErrorText(r); pin = "" }
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SetPinDialog(title: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose 4 to 8 digits. The person who holds this PIN is the one who can loosen your limits, so it should not be you.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = null },
                    singleLine = true,
                    label = { Text("New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it.filter { c -> c.isDigit() }.take(8); error = null },
                    singleLine = true,
                    label = { Text("Repeat PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !Guardian.validFormat(pin) -> error = "Use 4 to 8 digits."
                    pin != again -> error = "The two PINs don't match."
                    else -> { Guardian.setPin(pin); onDone() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val EVENT_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** Settings section: set, change or remove the PIN, plus the event log. */
@Composable
fun AccountabilitySection(gate: GuardianGate) {
    val v = Store.version
    val isSet = remember(v) { Guardian.isSet() }
    val extOnly = remember(v) { Store.guardianExtOnly }
    val events = remember(v) { Store.events().take(15) }
    var showSet by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    var adminTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { adminTick++ }
    val dpm = remember { ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { ComponentName(ctx, DoomAdminReceiver::class.java) }
    val adminOn = remember(adminTick, v) { dpm.isAdminActive(adminComponent) }

    Section("Accountability") {
        if (!isSet) {
            Text(
                "A guardian PIN locks every change that would loosen your limits: raising or removing a limit, " +
                    "pausing enforcement, ending a focus session, clearing the guardian log. Ask someone you trust to choose the PIN.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { showSet = true }, modifier = Modifier.fillMaxWidth()) { Text("Set a guardian PIN") }
        } else {
            Text("Guardian PIN is on.", color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Extra time needs the PIN")
                    Text(
                        "Replaces the write-a-reason button on the pause screen.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = extOnly, onCheckedChange = { on ->
                    if (on) Store.guardianExtOnly = true
                    else gate.run("Allow self-service extensions again") { Store.guardianExtOnly = false }
                })
            }
            OutlinedButton(onClick = { Guardian.lockNow() }, modifier = Modifier.fillMaxWidth()) { Text("Lock settings now") }
            OutlinedButton(onClick = { gate.run("Change the guardian PIN") { showSet = true } }, modifier = Modifier.fillMaxWidth()) {
                Text("Change PIN")
            }
            OutlinedButton(onClick = { gate.run("Remove the guardian PIN") { confirmRemove = true } }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove PIN")
            }
        }

        Text("Uninstall protection", style = MaterialTheme.typography.labelLarge)
        Text(
            "Android won't uninstall DoomGuard until this is switched off in its security settings, and that is written to the guardian log below. " +
                "It doesn't stop Force stop or revoking permissions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!adminOn) {
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            .putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Stops DoomGuard from being uninstalled on a whim. It gets no other powers."
                            )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Turn on uninstall protection") }
        } else {
            Text("Uninstall protection is on.", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(
                onClick = {
                    gate.run("Turn off uninstall protection") {
                        try { dpm.removeActiveAdmin(adminComponent) } catch (e: Exception) { /* already removed */ }
                        adminTick++
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Turn off uninstall protection") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Guardian log", style = MaterialTheme.typography.labelLarge)
            if (events.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (events.isEmpty()) {
            Text(
                "Overrides, PIN changes, permission changes and monitoring gaps will be listed here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            events.forEach { e ->
                val time = Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault()).format(EVENT_TIME)
                Text(
                    "$time · ${e.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.kind == "tamper") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showSet) {
        SetPinDialog(
            title = if (isSet) "Change guardian PIN" else "Set guardian PIN",
            onDismiss = { showSet = false },
            onDone = { showSet = false }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the guardian log?") },
            text = { Text("This deletes every entry. DoomGuard keeps one line saying the log was cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    // With a PIN set, this asks for it first. Without one it clears straight away.
                    gate.run("Clear the guardian log") { Store.clearEvents() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the guardian PIN?") },
            text = { Text("Limits can then be loosened without a PIN.") },
            confirmButton = { TextButton(onClick = { Guardian.clear(); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}
