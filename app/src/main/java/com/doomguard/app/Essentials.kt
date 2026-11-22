package com.doomguard.app

import android.content.Context
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Apps that must never be blocked: calling, messaging, maps and emergency tools, plus anything the user adds
 * with "Always allow" in the Apps tab. These can't be given a limit, so a limit can't trap you away from them.
 */
object Essentials {
    private val BUILT_IN = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.maps",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging"
    )

    @Volatile
    private var dynamic: Set<String>? = null

    /** Built-in list plus the phone's current default dialer and SMS app. Cached after the first call. */
    fun defaults(ctx: Context): Set<String> {
        dynamic?.let { return it }
        val extra = HashSet<String>()
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { extra += it }
        } catch (e: Exception) { /* not available on this phone */ }
        try {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { extra += it }
        } catch (e: Exception) { /* not available on this phone */ }
        val all = BUILT_IN + extra
        dynamic = all
        return all
    }

    /** Context-free check used inside [Store]: built-in list or the user's always-allowed list. */
    fun staticProtected(pkg: String): Boolean = pkg in BUILT_IN || pkg in Store.alwaysAllowed()

    fun isProtected(ctx: Context, pkg: String): Boolean = pkg in defaults(ctx) || pkg in Store.alwaysAllowed()
}
