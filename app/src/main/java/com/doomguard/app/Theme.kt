package com.doomguard.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val Dark = darkColorScheme(
    primary = Color(0xFFE8B86D),
    onPrimary = Color(0xFF2B1D00),
    secondary = Color(0xFF8FB8A8),
    onSecondary = Color(0xFF0B2A20),
    background = Color(0xFF14161A),
    onBackground = Color(0xFFEDE8DF),
    surface = Color(0xFF1C1F25),
    onSurface = Color(0xFFEDE8DF),
    surfaceVariant = Color(0xFF2A2E36),
    onSurfaceVariant = Color(0xFFB9B4AA),
    error = Color(0xFFFF8A80)
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2F6B58),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF6EE),
    onBackground = Color(0xFF211F1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF211F1A),
    surfaceVariant = Color(0xFFEFE8D8),
    onSurfaceVariant = Color(0xFF5C574C),
    error = Color(0xFFB3261E)
)

val QuoteFont = FontFamily.Serif

/** True when the app should currently be drawn dark, honouring the Light/Dark/System setting. */
@Composable
fun isAppDark(): Boolean {
    val mode = rememberStore { Store.themeMode }
    return when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun DoomGuardTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isAppDark()
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Re-reads from [Store] whenever any stored value changes. */
@Composable
fun <T> rememberStore(calc: () -> T): T {
    val v = Store.version
    return remember(v) { calc() }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
