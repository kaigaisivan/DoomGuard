@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.doomguard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(pad: PaddingValues) {
    val all = rememberStore { Store.activities() }
    var text by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(Category.WORK) }
    var mins by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { it.text.contains(query, ignoreCase = true) || it.cat.label.contains(query, ignoreCase = true) }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.day } }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Section("Log an activity") {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("What did you do?") },
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { c ->
                        FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = mins, onValueChange = { v -> mins = v.filter { it.isDigit() }.take(4) },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(130.dp)
                    )
                    Button(
                        onClick = {
                            if (text.isNotBlank()) {
                                Store.addActivity(text.trim(), cat, mins.toIntOrNull() ?: 0)
                                text = ""; mins = ""
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Add to log") }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search your history") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        if (all.isEmpty()) {
            item { Text("Nothing logged yet. Add the first thing you did today.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        grouped.forEach { (day, list) ->
            item(key = "h$day") {
                val d = LocalDate.parse(day)
                val title = if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("EEEE, d MMM"))
                val total = list.sumOf { it.minutes }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (total > 0) Text(fmtMinutes(total), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.text)
                            val time = Instant.ofEpochMilli(a.ts).atZone(ZoneId.systemDefault()).format(timeFmt)
                            Text(
                                "${a.cat.label} · $time" + if (a.minutes > 0) " · ${fmtMinutes(a.minutes)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { Store.deleteActivity(a.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}
