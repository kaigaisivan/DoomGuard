@file:OptIn(ExperimentalLayoutApi::class)

package com.doomguard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The side panel: current quote, theme filter, favourites and the user's own quotes. */
@Composable
fun QuotePanel() {
    val favorites = rememberStore { Store.favorites() }
    val q = Store.currentQuote
    var theme by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var newAuthor by remember { mutableStateOf("") }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Perspective", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

        if (q != null) {
            Text("“${q.text}”", style = MaterialTheme.typography.titleLarge, fontFamily = QuoteFont)
            Text("— ${q.author}", color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Store.currentQuote = Quotes.pick(theme, q.text) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Another quote")
                }
                IconButton(onClick = { Store.toggleFavorite(q.text) }) {
                    Icon(
                        if (q.text in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favourite"
                    )
                }
                TextButton(onClick = {
                    Store.hideQuote(q.text)
                    Store.currentQuote = Quotes.pick(theme, q.text)
                }) { Text("Hide") }
            }
        }

        Text("Show me quotes about", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = theme == null, onClick = { theme = null }, label = { Text("Anything") })
            Quotes.THEMES.forEach { t ->
                FilterChip(
                    selected = theme == t,
                    onClick = {
                        theme = if (theme == t) null else t
                        Store.currentQuote = Quotes.pick(theme, Store.currentQuote?.text)
                    },
                    label = { Text(t.replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        HorizontalDivider()

        Text("Favourites", style = MaterialTheme.typography.titleMedium)
        if (favorites.isEmpty()) {
            Text("Tap the heart on a quote to keep it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            favorites.forEach { text ->
                val full = Quotes.builtIn.firstOrNull { it.text == text } ?: Store.customQuotes().firstOrNull { it.text == text }
                Column {
                    Text("“$text”", fontFamily = QuoteFont, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("— ${full?.author ?: "Unknown"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { Store.toggleFavorite(text) }) { Text("Remove") }
                    }
                }
            }
        }

        HorizontalDivider()

        if (!adding) {
            TextButton(onClick = { adding = true }) { Text("+ Add my own quote") }
        } else {
            OutlinedTextField(
                value = newText, onValueChange = { newText = it },
                label = { Text("Quote or personal reminder") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )
            OutlinedTextField(
                value = newAuthor, onValueChange = { newAuthor = it },
                label = { Text("Author (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button(
                onClick = {
                    if (newText.isNotBlank()) {
                        Store.addCustomQuote(newText.trim(), newAuthor.trim())
                        newText = ""; newAuthor = ""; adding = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save quote") }
        }
    }
}
