package com.doomguard.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registers DoomGuard as a device administrator with no special powers. Android refuses to uninstall an active
 * administrator, so removing the app first needs a trip through Settings > Security > Device admin apps, which is
 * written to the guardian log. It does not stop "Force stop" or revoking permissions.
 */
class DoomAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Store.init(context)
        Store.logEvent("change", "Uninstall protection turned on")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Store.init(context)
        Store.logEvent("tamper", "Someone started turning off uninstall protection")
        return "Turning this off allows DoomGuard to be uninstalled. This is recorded in the guardian log."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Store.init(context)
        Store.logEvent("tamper", "Uninstall protection was turned off")
    }
}
