package com.wellmute.ui.setup

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.permissions.PermissionChecker
import com.wellmute.ui.common.openSettings

/** The special permissions Wellmute asks for, in the order the setup asks for them. */
enum class AppPermission(
    @StringRes val title: Int,
    @StringRes val why: Int,
    @StringRes val notUsedFor: Int,
    /** Android blocks it for apps installed outside an app store until "Allow restricted settings". */
    val restricted: Boolean,
    /** Wellmute works without it; the setup marks it "Optional" and skips it without a warning. */
    val optional: Boolean = false,
    /** Listed as bullet points after [why]. */
    val bullets: List<Int> = emptyList(),
) {
    USAGE(R.string.permission_usage_access, R.string.setup_usage_why, R.string.setup_usage_not, restricted = true),
    NOTIFICATIONS(R.string.permission_notification_listener, R.string.setup_notifications_why, R.string.setup_notifications_not, restricted = true),
    // Wellmute's own notifications: a runtime prompt, not a Settings page.
    POST_NOTIFICATIONS(R.string.permission_post_notifications, R.string.setup_post_notifications_why, R.string.setup_post_notifications_not, restricted = false, optional = true,
        bullets = listOf(R.string.setup_post_notifications_bedtime, R.string.setup_post_notifications_focus, R.string.setup_post_notifications_weekly),
    ),
    ACCESSIBILITY(R.string.permission_accessibility, R.string.setup_accessibility_why, R.string.setup_accessibility_not, restricted = true),
    BATTERY(R.string.permission_battery, R.string.setup_battery_why, R.string.setup_battery_not, restricted = false),
}

fun PermissionChecker.isGranted(permission: AppPermission): Boolean = when (permission) {
    AppPermission.USAGE -> hasUsageAccess()
    AppPermission.NOTIFICATIONS -> hasNotificationAccess()
    AppPermission.POST_NOTIFICATIONS -> canPostNotifications()
    AppPermission.ACCESSIBILITY -> hasAccessibility()
    AppPermission.BATTERY -> hasBatteryExemption()
}

/** Settings pages to grant [permission], most specific first. */
internal fun PermissionChecker.settingsFor(permission: AppPermission): Array<Intent> = when (permission) {
    AppPermission.USAGE -> arrayOf(usageAccessSettings(), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    AppPermission.NOTIFICATIONS -> arrayOf(notificationAccessSettings(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    AppPermission.POST_NOTIFICATIONS -> arrayOf(appNotificationSettings())
    AppPermission.ACCESSIBILITY -> arrayOf(accessibilitySettings())
    AppPermission.BATTERY -> arrayOf(batteryExemptionRequest(), batterySettings())
}

/**
 * What "Allow" does for each permission: Wellmute's own notifications get Android's prompt (and,
 * once Android stops showing it after a refusal, Wellmute's notification settings); the others
 * open their Settings page.
 */
@Composable
fun rememberPermissionRequest(checker: PermissionChecker): (AppPermission) -> Unit {
    val context = LocalContext.current
    val askedAt = remember { longArrayOf(0) }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        // Refused at once: Android didn't show its prompt (it stops after two refusals). A refusal
        // in the prompt itself is an answer, so only the first case opens the settings.
        if (!allowed && SystemClock.elapsedRealtime() - askedAt[0] < NO_PROMPT_MILLIS) context.openSettings(checker.appNotificationSettings())
    }
    return remember(checker) {
        { permission ->
            if (permission == AppPermission.POST_NOTIFICATIONS) {
                askedAt[0] = SystemClock.elapsedRealtime()
                prompt.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.openSettings(*checker.settingsFor(permission))
            }
        }
    }
}

/**
 * One card per permission: what it's for, what it's never used for, and whether it's granted.
 * [granted] is re-read by the caller whenever the screen resumes, since grants happen in Settings.
 */
fun LazyListScope.permissionCards(checker: PermissionChecker, granted: Map<AppPermission, Boolean>) {
    // Installed from a file and some blocked permission still missing: first, allow restricted settings.
    val fromFile = checker.installedFromFile()
    if (fromFile && AppPermission.entries.any { it.restricted && granted[it] != true }) {
        item(key = "restricted") { RestrictedSettingsCard(checker) }
    }
    items(AppPermission.entries.toList(), key = { it.name }) { permission ->
        PermissionCard(checker, permission, granted[permission] == true, fromFile)
    }
}

/**
 * Android blocks some permissions for apps installed from a file until "Allow restricted settings"
 * is turned on. Apps can't turn it on or open that option directly, so this opens Wellmute's App
 * info, where it's in the ⋮ menu.
 */
@Composable
private fun RestrictedSettingsCard(checker: PermissionChecker) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.restricted_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.restricted_why), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.restricted_steps), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { context.openSettings(checker.appInfoSettings()) }) {
                Text(stringResource(R.string.restricted_open_app_info))
            }
        }
    }
}

@Composable
private fun PermissionCard(checker: PermissionChecker, permission: AppPermission, granted: Boolean, fromFile: Boolean) {
    val request = rememberPermissionRequest(checker)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(permission.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.setup_granted), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(permission.why), style = MaterialTheme.typography.bodyMedium)
            PermissionBullets(permission, MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(permission.notUsedFor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                FilledTonalButton(onClick = { request(permission) }) {
                    Text(stringResource(R.string.setup_allow))
                }
                // Only where Android may block it: installed from a file.
                if (permission.restricted && fromFile) {
                    Text(
                        stringResource(R.string.setup_restricted_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** [AppPermission.bullets], one per line with a bullet in front. */
@Composable
internal fun PermissionBullets(permission: AppPermission, style: TextStyle) {
    if (permission.bullets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        permission.bullets.forEach { bullet ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = style)
                Text(stringResource(bullet), style = style)
            }
        }
    }
}

/** Quicker than anyone could answer Android's prompt: it wasn't shown. */
private const val NO_PROMPT_MILLIS = 500L
