package com.doomguard.app

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** One line of the "All apps today" list: icon, name, time, a bar, and a Limit button if the app is unlimited. */
@Composable
fun AppUsageRow(pkg: String, ms: Long, fraction: Float, limitMin: Int?) {
    val ctx = LocalContext.current
    val label = remember(pkg) { appLabel(ctx, pkg) }
    val icon = remember(pkg) {
        try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() } catch (e: Exception) { null }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
        else Spacer(Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(fmtDurationShort(ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (limitMin != null) {
            Text("Limit ${fmtMinutes(limitMin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (Essentials.isProtected(ctx, pkg)) {
            Text("Always allowed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        } else {
            TextButton(onClick = {
                if (Store.setLimit(pkg, 30)) {
                    Toast.makeText(ctx, "$label limited to 30 min a day. Change it in the Apps tab.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Limit") }
        }
    }
}
