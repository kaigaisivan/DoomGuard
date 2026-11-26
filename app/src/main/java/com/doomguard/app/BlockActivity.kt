@file:OptIn(ExperimentalLayoutApi::class)

package com.doomguard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Minutes granted by the 1st, 2nd and 3rd "continue" of the day. After that the app is locked. */
private val EXT_MINUTES = listOf(5, 5, 3)
private val EXT_WAIT_SECONDS = listOf(15, 30, 60)
private const val LOCK_MINUTES = 30L

private val MOODS = listOf("Bored", "Stressed", "Procrastinating", "Lonely", "Tired", "Just habit")

class BlockActivity : ComponentActivity() {
    companion object {
        @Volatile
        var showing = false
    }

    private var pkg = ""
    private var mood: String? = null
    private var reason = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        pkg = intent.getStringExtra("pkg") ?: run { finish(); return }
        val mode = intent.getStringExtra("mode") ?: "LIMIT"
        val used = intent.getLongExtra("used", 0L)
        val limitMin = intent.getIntExtra("limit", 0)
        val total = intent.getBooleanExtra("total", false)

        val state = Store.pkgState(pkg)
        val strict = Store.strictActive()
        val guardianOnly = Guardian.isSet() && Store.guardianExtOnly
        val canContinue = mode == "LIMIT" && !strict && !guardianOnly && state.ext < EXT_MINUTES.size

        // Out of extensions (or strict mode): lock the app so reopening it doesn't dodge the rule.
        if (mode == "LIMIT" && !canContinue) {
            val until = if (strict) endOfDay() else System.currentTimeMillis() + LOCK_MINUTES * 60_000L
            if (state.lockUntil < until) {
                state.lockUntil = until
                Store.savePkgState(pkg, state)
            }
        }
        val lockedUntil = state.lockUntil
        val waitSeconds = EXT_WAIT_SECONDS.getOrElse(state.ext) { 60 }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        setContent {
            DoomGuardTheme(forceDark = true) {
                BlockScreen(
                    label = appLabel(this, pkg),
                    mode = mode,
                    usedMs = used,
                    limitMin = limitMin,
                    canContinue = canContinue,
                    strict = strict,
                    waitSeconds = waitSeconds,
                    extraMinutes = EXT_MINUTES.getOrElse(state.ext) { 0 },
                    lockedUntil = lockedUntil,
                    onMood = { mood = it },
                    onReason = { reason = it },
                    total = total,
                    guardian = Guardian.isSet(),
                    guardianOnly = guardianOnly,
                    onHome = { leave() },
                    onContinue = { continueUsing() },
                    onGuardian = { pin, minutes, note -> guardianOverride(pin, minutes, note) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        showing = true
    }

    override fun onStop() {
        showing = false
        // Never leave this screen lingering in the background.
        if (!isFinishing) finish()
        super.onStop()
    }

    private fun saveTrigger() {
        if (mood != null || reason.isNotBlank()) {
            Store.addTrigger(TriggerEntry(System.currentTimeMillis(), pkg, mood ?: "unspecified", reason.trim()))
            mood = null
            reason = ""
        }
    }

    private fun leave() {
        saveTrigger()
        goHome(this)
        finish()
    }

    /** The guardian types the PIN on this screen to grant more time. Does not open the settings unlock window. */
    private fun guardianOverride(pin: String, minutes: Int, note: String): PinResult {
        val r = Guardian.verify(pin, unlock = false)
        if (r is PinResult.Ok) {
            val now = System.currentTimeMillis()
            val s = Store.pkgState(pkg)
            val until = now + minutes * 60_000L
            s.guardianUntil = until
            s.lockUntil = 0
            s.okUntil = until
            s.warned = true
            Store.savePkgState(pkg, s)
            Store.logEvent(
                "override",
                "Guardian gave ${appLabel(this, pkg)} $minutes more minutes" + if (note.isNotBlank()) " (${note.trim()})" else ""
            )
            saveTrigger()
            finish() // reveals the app underneath
        }
        return r
    }

    private fun continueUsing() {
        val s = Store.pkgState(pkg)
        val mins = EXT_MINUTES.getOrElse(s.ext) { return }
        saveTrigger()
        s.ext++
        s.snoozeUntil = System.currentTimeMillis() + mins * 60_000L
        s.okUntil = s.snoozeUntil // don't also show the open-app pause when the app reappears
        s.warned = true
        Store.savePkgState(pkg, s)
        finish() // reveals the app underneath again
    }
}

@Composable
private fun BlockScreen(
    label: String,
    mode: String,
    usedMs: Long,
    limitMin: Int,
    canContinue: Boolean,
    strict: Boolean,
    waitSeconds: Int,
    extraMinutes: Int,
    lockedUntil: Long,
    total: Boolean,
    guardian: Boolean,
    guardianOnly: Boolean,
    onMood: (String?) -> Unit,
    onReason: (String) -> Unit,
    onHome: () -> Unit,
    onContinue: () -> Unit,
    onGuardian: (String, Int, String) -> PinResult
) {
    // Up to three ideas: your own intentions for today first, then small things that fit the time of day.
    val alternatives = remember {
        val mine = Store.tasksToday().filter { !it.done }.take(2).map { it.text }
        val hour = java.time.LocalTime.now().hour
        val byTime = when (hour) {
            in 5..10 -> listOf("Make breakfast or a hot drink", "Open a window and get some daylight", "Plan your first task for the morning")
            in 11..16 -> listOf("Eat something proper", "Walk around the block", "Clear one small job from your list")
            in 17..21 -> listOf("Start cooking or tidy up for ten minutes", "Call or message someone you care about", "Prepare what you need for tomorrow")
            else -> listOf("Put the phone on charge across the room", "Drink some water and get ready for bed", "Write tomorrow's top task on paper")
        }
        val general = listOf(
            "Drink a glass of water", "Stretch for two minutes", "Read two pages of a book",
            "Step outside for five minutes", "Tidy your desk or bed", "Write down what you're avoiding"
        )
        (mine + (byTime + general).shuffled()).take(3)
    }

    var selectedMood by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var left by remember { mutableIntStateOf(waitSeconds) }
    LaunchedEffect(Unit) {
        while (left > 0) { delay(1000); left-- }
    }
    var inhale by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { delay(4000); inhale = !inhale }
    }
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    val title = when (mode) {
        "SESSION" -> "Focus session in progress"
        "FOCUS" -> "Focus hours"
        "LOCKED" -> "$label is locked"
        else -> if (total) "Daily total reached" else "Time's up on $label"
    }
    val subtitle = when (mode) {
        "SESSION" -> {
            val mins = ((Store.focusSessionEnd - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            val what = Store.focusSessionLabel
            "About $mins min left" + if (what.isNotBlank()) " on: $what" else ""
        }
        "FOCUS" -> "You asked not to be pulled into $label right now."
        "LOCKED" -> {
            val mins = ((lockedUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            if (strict) "Strict mode: done for today." else "Cooling down for about $mins more minutes."
        }
        else -> (if (total) "You've used ${fmtDuration(usedMs)} across your limited apps today. Your total cap is ${fmtMinutes(limitMin)}."
        else "You've used ${fmtDuration(usedMs)} today. Your limit is ${fmtMinutes(limitMin)}.") +
                if (!canContinue) (if (guardianOnly) " Extra time needs your guardian." else " No more extensions available.") else ""
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            Box(Modifier.height(170.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(150.dp).scale(scale).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), CircleShape)
                )
                Text(if (inhale) "Breathe in" else "Breathe out", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Instead, try", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    alternatives.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge) }
                }
            }

            if (mode == "LIMIT" || mode == "FOCUS") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What made you open it? (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MOODS.forEach { m ->
                            FilterChip(
                                selected = selectedMood == m,
                                onClick = {
                                    selectedMood = if (selectedMood == m) null else m
                                    onMood(selectedMood)
                                },
                                label = { Text(m) }
                            )
                        }
                    }
                }
            }

            Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                Text("Close it and go home")
            }

            if (canContinue) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Still want more time? Say why.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it; onReason(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("I need this because…") },
                        minLines = 2
                    )
                    val ready = left == 0 && reason.trim().length >= 8
                    OutlinedButton(onClick = onContinue, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                left > 0 -> "Wait ${left}s…"
                                reason.trim().length < 8 -> "Write a real reason first"
                                else -> "Continue for $extraMinutes more minutes"
                            }
                        )
                    }
                }
            }

            if (guardian) {
                var open by remember { mutableStateOf(false) }
                var pin by remember { mutableStateOf("") }
                var gMins by remember { mutableIntStateOf(15) }
                var gNote by remember { mutableStateOf("") }
                var gError by remember { mutableStateOf<String?>(null) }
                if (!open) {
                    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Guardian override") }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Guardian: enter your PIN to give more time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 15, 30, 60).forEach { m ->
                                FilterChip(selected = gMins == m, onClick = { gMins = m }, label = { Text("$m min") })
                            }
                        }
                        OutlinedTextField(
                            value = gNote,
                            onValueChange = { gNote = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Note (optional)") }
                        )
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); gError = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Guardian PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            isError = gError != null
                        )
                        if (gError != null) Text(gError ?: "", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = {
                                val r = onGuardian(pin, gMins, gNote)
                                if (r !is PinResult.Ok) { gError = pinErrorText(r); pin = "" }
                            },
                            enabled = pin.length >= 4,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Give $gMins minutes") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}