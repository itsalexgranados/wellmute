package com.wellmute.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wellmute.Links
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wellmute.R
import com.wellmute.ui.common.formatDuration
import com.wellmute.WellmuteApp
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.openLink
import com.wellmute.notifications.WeeklySummaryWorker
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.wellmute.ui.setup.rememberPermissionStatus

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenUsageLab: () -> Unit,
    onOpenNotificationLab: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val granted = rememberPermissionStatus(container.permissions)
    val versionName = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val isDebugBuild = remember(context) {
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    val notifPrefs by container.notifications.prefs.collectAsStateWithLifecycle()
    // Days since Wellmute's first day of history, today included; null before there is any.
    val firstDay by container.history.observeFirstDay().collectAsStateWithLifecycle(null)
    val daysUsed = firstDay?.let { (ChronoUnit.DAYS.between(it, LocalDate.now()) + 1).toInt() }
    // Re-read when coming back, in case notifications were allowed or blocked in Android's settings.
    var canPost by remember { mutableStateOf(container.notifications.canPost()) }
    LifecycleResumeEffect(Unit) {
        canPost = container.notifications.canPost()
        onPauseOrDispose { }
    }


    SubpageScaffold(title = R.string.nav_settings, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            // Phase 2 prototype tools; never shown in release builds.
            if (isDebugBuild) {
                item { SectionHeader(R.string.settings_developer) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lab_title)) },
                        supportingContent = { Text(stringResource(R.string.lab_summary)) },
                        leadingContent = { Icon(Icons.Outlined.Science, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenUsageLab),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notification_lab_title)) },
                        supportingContent = { Text(stringResource(R.string.notification_lab_summary)) },
                        leadingContent = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenNotificationLab),
                    )
                }
                item {
                    val sample = stringResource(R.string.reminders_bubble, "Calculator", formatDuration(5 * 60_000L))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lab_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.lab_bubble_summary)) },
                        leadingContent = { Icon(Icons.Outlined.Science, contentDescription = null) },
                        modifier = Modifier.clickable { container.usageReminders.showBubble?.invoke(sample) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lab_weekly_title)) },
                        supportingContent = { Text(stringResource(R.string.lab_weekly_summary)) },
                        leadingContent = { Icon(Icons.Outlined.Science, contentDescription = null) },
                        modifier = Modifier.clickable {
                            WeeklySummaryWorker.sendNow(context)
                        },
                    )
                }
            }

            // Wellmute's own notifications, in their own page: "3 of 3 on", or not allowed by Android.
            item { SectionHeader(R.string.settings_general) }
            item {
                val on = listOf(notifPrefs.bedtimeOn, notifPrefs.focusOn, notifPrefs.weeklySummary).count { it }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                    supportingContent = {
                        Text(if (canPost) stringResource(R.string.settings_notifications_count, on, 3) else stringResource(R.string.settings_notifications_blocked))
                    },
                    leadingContent = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenNotifications),
                )
            }

            item { SectionHeader(R.string.settings_privacy) }
            item {
                val allowed = granted.values.count { it }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_permissions)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.settings_permissions_summary, granted.size, allowed, granted.size)) },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    trailingContent = {
                        if (allowed < granted.size) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onOpenPermissions),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                    supportingContent = { Text(stringResource(R.string.settings_privacy_summary)) },
                    leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.opens_in_browser)) },
                    modifier = Modifier.clickable { context.openLink(Links.PRIVACY) },
                )
            }

            item { SectionHeader(R.string.settings_about) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_support)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // How long Wellmute has been in use (since its first day of history), in bold.
                            daysUsed?.let { days ->
                                Text(
                                    withBold(
                                        stringResource(R.string.settings_support_used),
                                        pluralStringResource(R.plurals.settings_support_days, days, days),
                                    ),
                                )
                            }
                            Text(stringResource(R.string.settings_support_open_source))
                            Text(stringResource(R.string.settings_support_few_dollars))
                            FilledTonalButton(onClick = { context.openLink(Links.DONATE) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_donate))
                            }
                        }
                    },
                    // Red, so supporting the project stands out among the settings.
                    leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = SupportRed) },
                )
            }
            item {
                InfoRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_version, versionName),
                    summary = stringResource(R.string.settings_license),
                )
            }
            item {
                // "Made by Alex Granados", the name styled as a link to the author's site.
                val name = stringResource(R.string.settings_author_name)
                val line = stringResource(R.string.settings_made_by, "%1\$s")
                val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                ListItem(
                    headlineContent = {
                        Text(
                            buildAnnotatedString {
                                val at = line.indexOf("%1\$s")
                                append(line.substring(0, at))
                                withStyle(link) { append(name) }
                                append(line.substring(at + 4))
                            },
                        )
                    },
                    // No icon, but the same indent as the rows above, so the text lines up.
                    leadingContent = { Spacer(Modifier.size(24.dp)) },
                    modifier = Modifier.clickable(onClickLabel = name) { context.openLink(Links.AUTHOR) },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, summary: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

/** The heart next to "Support the project". */
private val SupportRed = Color(0xFFE5484D)

/** [text] with [highlight] in bold, where [text] holds "%1$s" in its place (so translators can move it). */
private fun withBold(text: String, highlight: String): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf("%1\$s")
    if (at < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(highlight) }
    append(text.substring(at + 4))
}
