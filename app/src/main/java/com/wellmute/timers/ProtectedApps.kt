package com.wellmute.timers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Apps that can never get a timer or be paused, because pausing them could lock the user out
 * of their phone, out of removing Wellmute, or out of an emergency call.
 *
 * Worked out from the device's current defaults (launcher, dialer…) on every check, so changing
 * a default app is covered too.
 */
class ProtectedApps(private val context: Context) {

    private val packageManager = context.packageManager

    @Volatile
    private var cache: Pair<Long, Set<String>>? = null

    fun isProtected(packageName: String): Boolean = packageName in protectedPackages()

    /** Recomputed at most once a minute: app lists ask for every row. */
    private fun protectedPackages(): Set<String> {
        val now = System.currentTimeMillis()
        cache?.let { (computedAt, packages) -> if (now - computedAt < CACHE_MILLIS) return packages }
        return computeProtectedPackages().also { cache = now to it }
    }

    private fun computeProtectedPackages(): Set<String> = buildSet {
        add(context.packageName)
        addAll(KNOWN_SYSTEM_PACKAGES)
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
        addAll(handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        addAll(handlers(Intent(Settings.ACTION_SETTINGS)))
        addAll(handlers(Intent(Intent.ACTION_DIAL)))
        addAll(handlers(Intent(ACTION_EMERGENCY_ASSISTANCE)))
        addAll(handlers(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", context.packageName, null))))
    }

    private fun handlers(intent: Intent): List<String> =
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }

    private companion object {
        const val CACHE_MILLIS = 60_000L
        const val ACTION_EMERGENCY_ASSISTANCE = "android.telephony.action.EMERGENCY_ASSISTANCE"

        /** System parts with no public intent to find them by. */
        val KNOWN_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.emergency",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.apps.safetyhub",
        )
    }
}
