package com.doomguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer release, at most once a day, and tells the user. It only reads the latest version number
 * from the public releases API and sends nothing about the person or the phone. It never downloads or installs anything:
 * the user taps a button that opens the release page in the browser.
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/kaigaisivan/DoomGuard/releases/latest"
    private const val RELEASES = "https://github.com/kaigaisivan/DoomGuard/releases/latest"
    private const val TRUSTED_PREFIX = "https://github.com/kaigaisivan/DoomGuard"

    @Volatile private var running = false
    @Volatile private var lastTry = 0L

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** True if version [a] is newer than [b]. Compares dotted numbers, so 1.10 is newer than 1.9. */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V").split('.', '-', '+')
            .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** The newer version's name if there is one the user hasn't dismissed, else null. */
    fun available(ctx: Context): String? {
        if (!Store.updateCheckOn) return null
        val latest = Store.updateLatest
        if (latest.isBlank() || latest == Store.updateDismissed) return null
        return if (isNewer(latest, installedVersion(ctx))) latest else null
    }

    fun openDownload(ctx: Context) {
        val url = Store.updateUrl.ifBlank { RELEASES }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("DoomGuard", "no browser to open the update page", e)
        }
    }

    /** Cheap to call often: does a check at most once a day (and at most once an hour if the network is failing). */
    fun runIfDue(ctx: Context) {
        if (!Store.updateCheckOn) return
        val now = System.currentTimeMillis()
        if (now - Store.updateLast < DAY_MS) return
        if (now - lastTry < 60 * 60_000L) return
        lastTry = now
        checkNow(ctx, null)
    }

    /** Runs the check on its own thread. [onDone] gets true if GitHub answered, and is called on the main thread. */
    fun checkNow(ctx: Context, onDone: ((Boolean) -> Unit)?) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread({
            var ok = false
            try {
                Store.init(app)
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "DoomGuard")
                try {
                    if (c.responseCode == 200) {
                        val body = c.inputStream.bufferedReader().use { it.readText() }
                        val o = JSONObject(body)
                        val tag = o.optString("tag_name")
                        val page = o.optString("html_url")
                        if (tag.isNotBlank()) {
                            Store.updateLatest = tag
                            // Only ever open a page on this project's own GitHub repo.
                            Store.updateUrl = if (page.startsWith(TRUSTED_PREFIX)) page else RELEASES
                            Store.updateLast = System.currentTimeMillis()
                            ok = true
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w("DoomGuard", "update check failed", e)
            } finally {
                running = false
                if (onDone != null) Handler(Looper.getMainLooper()).post { onDone(ok) }
            }
        }, "update-check").start()
    }
}

/** Settings section: installed version, the on/off switch, "Check now" and the update button. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val v = Store.version
    val on = remember(v) { Store.updateCheckOn }
    val latest = remember(v) { Store.updateLatest }
    val installed = remember { UpdateChecker.installedVersion(ctx) }
    val newer = latest.isNotBlank() && UpdateChecker.isNewer(latest, installed)
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Section("Updates") {
        Text("Installed version $installed")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Check automatically")
                Text(
                    "Once a day, DoomGuard asks GitHub for the newest version number. Nothing about you or your phone is sent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = on, onCheckedChange = { Store.updateCheckOn = it })
        }
        if (newer) {
            Text("Version ${latest.removePrefix("v")} is available.", color = MaterialTheme.colorScheme.primary)
            Button(onClick = { UpdateChecker.openDownload(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Get the update") }
            Text(
                "It opens the download page. Install the new file over this one and your settings and history stay.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (latest.isNotBlank()) {
            Text("You have the latest version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) {
            Text("Couldn't reach GitHub. Check your connection and try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = {
                checking = true
                failed = false
                UpdateChecker.checkNow(ctx) { ok -> checking = false; failed = !ok }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (checking) "Checking…" else "Check for updates") }
    }
}

/** Card on the Today screen when a newer version is out. "Later" hides it until an even newer one appears. */
@Composable
fun UpdateBanner(version: String) {
    val ctx = LocalContext.current
    Section("Update available") {
        Text("Version ${version.removePrefix("v")} is ready. Your settings and history stay when you install it.")
        Button(onClick = { UpdateChecker.openDownload(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Get the update") }
        OutlinedButton(onClick = { Store.updateDismissed = version }, modifier = Modifier.fillMaxWidth()) { Text("Later") }
    }
}
