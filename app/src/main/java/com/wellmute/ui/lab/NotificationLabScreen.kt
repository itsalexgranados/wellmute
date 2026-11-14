package com.wellmute.ui.lab

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.notifications.NotificationLab
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Phase 2 prototype: tries both notification filtering strategies and shows how fast they act. */
@Composable
fun NotificationLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val lab = container.notificationLab
    val strategy by lab.strategy.collectAsStateWithLifecycle()
    val targets by lab.targets.collectAsStateWithLifecycle()
    val log by lab.log.collectAsStateWithLifecycle()

    // Permission state lives in system Settings; re-read it whenever we come back from there.
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }
    val requestPostPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeCount++ }
    val access = remember(resumeCount) {
        listOf(
            Triple(R.string.permission_notification_listener, lab.hasListenerAccess(), Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Triple(R.string.permission_dnd, lab.hasPolicyAccess(), Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )
    }
    val canPost = remember(resumeCount) { lab.canPostNotifications() }
    val relayBypasses = remember(resumeCount, strategy) { lab.relayChannelBypassesModes() }
    val apps = (targets + log.map { it.packageName }).distinct().sorted()

    SubpageScaffold(title = R.string.notification_lab_title, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item { SectionHeader(R.string.settings_permissions) }
            items(access) { (label, granted, action) ->
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    supportingContent = { Text(stringResource(if (granted) R.string.notification_lab_granted else R.string.notification_lab_missing)) },
                    trailingContent = {
                        if (!granted) TextButton(onClick = { context.startActivity(Intent(action)) }) { Text(stringResource(R.string.notification_lab_open)) }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notification_lab_post)) },
                    supportingContent = { Text(stringResource(if (canPost) R.string.notification_lab_granted else R.string.notification_lab_missing)) },
                    trailingContent = {
                        if (!canPost) {
                            TextButton(onClick = { requestPostPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text(stringResource(R.string.notification_lab_open))
                            }
                        }
                    },
                )
            }

            item { SectionHeader(R.string.notification_lab_strategy) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        NotificationLab.Strategy.OFF to R.string.notification_lab_off,
                        NotificationLab.Strategy.BLOCKLIST to R.string.notification_lab_blocklist,
                        NotificationLab.Strategy.ALLOWLIST to R.string.notification_lab_allowlist,
                    ).forEach { (option, label) ->
                        FilterChip(selected = strategy == option, onClick = { lab.setStrategy(option) }, label = { Text(stringResource(label)) })
                    }
                }
            }
            item {
                val explanation = when (strategy) {
                    NotificationLab.Strategy.OFF -> R.string.notification_lab_off_help
                    NotificationLab.Strategy.BLOCKLIST -> R.string.notification_lab_blocklist_help
                    NotificationLab.Strategy.ALLOWLIST -> R.string.notification_lab_allowlist_help
                }
                Text(stringResource(explanation), Modifier.padding(16.dp))
            }
            if (strategy == NotificationLab.Strategy.ALLOWLIST) {
                item {
                    Text(
                        stringResource(if (relayBypasses) R.string.notification_lab_relay_ok else R.string.notification_lab_relay_blocked),
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item { SectionHeader(R.string.notification_lab_targets) }
            items(apps, key = { it }) { pkg ->
                ListItem(
                    headlineContent = { Text(container.appInfo.labelFor(pkg)) },
                    supportingContent = { Text(pkg) },
                    trailingContent = { Checkbox(checked = pkg in targets, onCheckedChange = { lab.toggleTarget(pkg) }) },
                )
            }

            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { lab.sendTestNotification(delayMillis = 5_000) }) {
                        Text(stringResource(R.string.notification_lab_send_test))
                    }
                    OutlinedButton(onClick = lab::clearLog) { Text(stringResource(R.string.notification_lab_clear)) }
                }
            }

            item { SectionHeader(R.string.notification_lab_log) }
            items(log) { entry ->
                ListItem(
                    headlineContent = { Text("${container.appInfo.labelFor(entry.packageName)}: ${entry.title}") },
                    supportingContent = {
                        val action = when (entry.action) {
                            NotificationLab.Action.PASSED -> R.string.notification_lab_passed
                            NotificationLab.Action.CANCELLED -> R.string.notification_lab_cancelled
                            NotificationLab.Action.RELAYED -> R.string.notification_lab_relayed
                            NotificationLab.Action.LEFT_SILENT -> R.string.notification_lab_left_silent
                        }
                        Text(
                            stringResource(
                                R.string.notification_lab_entry,
                                timeFormat.format(Instant.ofEpochMilli(entry.postedAt)),
                                stringResource(action),
                                entry.latencyMillis,
                            ),
                        )
                    },
                )
            }
        }
    }
}
