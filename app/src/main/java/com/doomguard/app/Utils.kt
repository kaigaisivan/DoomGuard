package com.doomguard.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val DAY_MS = 86_400_000L

fun today(): String = LocalDate.now().toString()

fun midnight(now: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}

fun endOfDay(now: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
}

fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

fun goHome(ctx: Context) {
    ctx.startActivity(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun fmtDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun fmtMinutes(m: Int): String = fmtDuration(m * 60_000L)

/** Like [fmtDuration] but says "<1m" instead of "0m" for short sessions. */
fun fmtDurationShort(ms: Long): String = if (ms < 60_000L) "<1m" else fmtDuration(ms)

/** Packages of apps a person actually opens (they have a launcher icon), minus the home screen and this app. */
fun launcherPackages(ctx: Context): Set<String> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pkgs = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.toMutableSet()
    val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    if (home != null) pkgs.remove(home)
    pkgs.remove(ctx.packageName)
    return pkgs
}

object Perms {
    @Suppress("DEPRECATION")
    fun hasUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun openUsage(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openOverlay(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Xiaomi (MIUI/HyperOS) has its own switch, "Display pop-up windows while running in the background", that is
     * separate from "Display over other apps". Without it the pause screen can't appear over another app.
     * Returns null when this isn't a Xiaomi phone or the state can't be read.
     */
    fun miuiPopupAllowed(ctx: Context): Boolean? {
        if (!Build.MANUFACTURER.equals("xiaomi", true) && !Build.MANUFACTURER.equals("redmi", true) &&
            !Build.MANUFACTURER.equals("poco", true)) return null
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            (m.invoke(ops, 10021, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            null
        }
    }

    fun openMiuiPermissions(ctx: Context) {
        try {
            ctx.startActivity(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
