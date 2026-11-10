package com.wellmute.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.wellmute.notifications.WellmuteNotificationListener
import com.wellmute.timers.ForegroundAppService
import com.wellmute.usage.UsageRepository

/** Which special permissions the user has granted, and where in Settings to grant the others. */
class PermissionChecker(private val context: Context, private val usage: UsageRepository) {

    private val listener = ComponentName(context, WellmuteNotificationListener::class.java)

    /**
     * Installed from an APK file (Files app or a browser download) rather than an app store or adb.
     * Android then blocks usage access, notification access and accessibility until the user turns
     * on "Allow restricted settings" in Wellmute's App info. Android doesn't say whether they
     * already have, so this only tells when it may be needed.
     */
    fun installedFromFile(): Boolean {
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    /** Wellmute's App info page, whose ⋮ menu holds "Allow restricted settings". */
    fun appInfoSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun hasUsageAccess(): Boolean = usage.hasUsageAccess()

    fun hasNotificationAccess(): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    fun hasAccessibility(): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { info -> info.packageName == context.packageName && info.name == ForegroundAppService::class.java.name } }

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Whether Wellmute may post its own notifications (Bedtime and Focus "on until", the weekly summary). */
    fun canPostNotifications(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Wellmute's notification settings, for when Android no longer shows the permission prompt. */
    fun appNotificationSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun hasPolicyAccess(): Boolean = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Exempt from battery optimization, so Android doesn't stop Wellmute in the background. */
    fun hasBatteryExemption(): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // Timers, modes and history need to keep running; the onboarding explains why.
    fun batteryExemptionRequest(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun policyAccessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun usageAccessSettings(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun notificationAccessSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
}
