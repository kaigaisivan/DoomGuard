package com.doomguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Writes a backup file into a folder the user picked (Storage Access Framework), once a week, keeping the last four. */
object AutoBackup {
    private const val INTERVAL_MS = 7 * DAY_MS
    private const val KEEP = 4
    private const val PREFIX = "doomguard-auto-"

    @Volatile
    private var lastTry = 0L

    fun due(): Boolean =
        Store.autoBackupOn && Store.autoBackupUri.isNotEmpty() &&
            System.currentTimeMillis() - Store.autoBackupLast >= INTERVAL_MS

    /** Cheap to call often: runs at most once an hour even if the folder is broken. */
    fun runIfDue(ctx: Context) {
        if (!due()) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 60 * 60_000L) return
        lastTry = now
        run(ctx)
    }

    @Synchronized
    fun run(ctx: Context): Boolean {
        val uriStr = Store.autoBackupUri
        if (uriStr.isEmpty()) return false
        return try {
            val tree = Uri.parse(uriStr)
            val resolver = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = "$PREFIX${today()}.json"
            // Replace a file from earlier today rather than ending up with "(1)" copies.
            children(ctx, tree).filter { it.second == name }.forEach { DocumentsContract.deleteDocument(resolver, it.first) }
            val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name) ?: return false
            val written = resolver.openOutputStream(doc)?.use { it.write(Store.exportAll().toByteArray()); true } ?: false
            if (!written) return false
            children(ctx, tree)
                .filter { it.second.startsWith(PREFIX) }
                .sortedByDescending { it.second }
                .drop(KEEP)
                .forEach { DocumentsContract.deleteDocument(resolver, it.first) }
            Store.autoBackupLast = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w("DoomGuard", "automatic backup failed", e)
            false
        }
    }

    private fun children(ctx: Context, tree: Uri): List<Pair<Uri, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<Uri, String>>()
        ctx.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to (c.getString(1) ?: "")
            }
        }
        return out
    }
}

private val BACKUP_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

@Composable
fun AutoBackupSection() {
    val ctx = LocalContext.current
    val v = Store.version
    val on = remember(v) { Store.autoBackupOn }
    val uriStr = remember(v) { Store.autoBackupUri }
    val last = remember(v) { Store.autoBackupLast }
    val scope = rememberCoroutineScope()

    fun backupNow() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AutoBackup.run(ctx) }
            Toast.makeText(ctx, if (ok) "Backup saved" else "Could not write to that folder. Pick it again.", Toast.LENGTH_SHORT).show()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("DoomGuard", "could not keep folder permission", e)
            }
            Store.autoBackupUri = uri.toString()
            Store.autoBackupOn = true
            backupNow()
        }
    }

    Section("Automatic backup") {
        Text(
            "Saves a copy of your data to a folder you choose every 7 days and keeps the last 4, so losing the phone doesn't lose " +
                "your streak and log. Pick a folder that syncs, such as one in Google Drive if it appears in the picker. " +
                "The guardian PIN is never included.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (uriStr.isEmpty()) {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Choose a backup folder") }
        } else {
            Text("Folder: ${Uri.parse(uriStr).lastPathSegment ?: uriStr}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Back up every 7 days")
                    Text(
                        if (last == 0L) "No backup saved yet."
                        else "Last backup: " + Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(BACKUP_TIME),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = on, onCheckedChange = { Store.autoBackupOn = it })
            }
            OutlinedButton(onClick = { backupNow() }, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Change folder") }
        }
    }
}
