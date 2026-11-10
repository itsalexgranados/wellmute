package com.wellmute.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.platform.LocalContext
import com.wellmute.R
import com.wellmute.modes.DndPolicy
import com.wellmute.modes.Senders

/** "Who can reach you", shared by Bedtime and Do Not Disturb schedules. Each page is one group. */
fun LazyListScope.dndPeopleItems(policy: DndPolicy, onChange: (DndPolicy) -> Unit) {
    item { SendersRow(R.string.bedtime_calls_from, policy.callsFrom) { onChange(policy.copy(callsFrom = it)) } }
    // Who "Starred contacts only" means, right under the choice that picked it (once, if both did).
    if (policy.callsFrom == Senders.STARRED) {
        item(key = "starred") { StarredContactsList(Modifier.padding(horizontal = 16.dp)) }
    }
    item { SwitchRow(R.string.bedtime_repeat_callers, R.string.bedtime_repeat_callers_summary, policy.repeatCallers) { onChange(policy.copy(repeatCallers = it)) } }
    item { SendersRow(R.string.bedtime_messages_from, policy.messagesFrom) { onChange(policy.copy(messagesFrom = it)) } }
    if (policy.messagesFrom == Senders.STARRED && policy.callsFrom != Senders.STARRED) {
        item(key = "starred") { StarredContactsList(Modifier.padding(horizontal = 16.dp)) }
    }
    item {
        SwitchRow(R.string.bedtime_conversations, R.string.bedtime_conversations_summary, policy.priorityConversations) {
            onChange(policy.copy(priorityConversations = it))
        }
    }
    // Which conversations are priority is Android's own list (other apps' settings, which apps can't
    // read or change), so this opens Android's Conversations page, where it's shown and changed.
    if (policy.priorityConversations) {
        item(key = "conversations") {
            val context = LocalContext.current
            ListItem(
                headlineContent = { Text(stringResource(R.string.conversations_manage)) },
                supportingContent = { Text(stringResource(R.string.conversations_manage_summary)) },
                leadingContent = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                modifier = Modifier.clickable {
                    context.openSettings(Intent(CONVERSATION_SETTINGS), Intent("android.settings.NOTIFICATION_SETTINGS"))
                },
            )
        }
    }
}

/** "Sounds that still play", shared by Bedtime and Do Not Disturb schedules. */
fun LazyListScope.dndSoundsItems(policy: DndPolicy, onChange: (DndPolicy) -> Unit) {
    item { SwitchRow(R.string.bedtime_alarms, R.string.bedtime_alarms_summary, policy.alarms) { onChange(policy.copy(alarms = it)) } }
    item { SwitchRow(R.string.bedtime_media, R.string.bedtime_media_summary, policy.media) { onChange(policy.copy(media = it)) } }
    item { SwitchRow(R.string.bedtime_touch_sounds, R.string.bedtime_touch_sounds_summary, policy.touchSounds) { onChange(policy.copy(touchSounds = it)) } }
    item { SwitchRow(R.string.bedtime_reminders, R.string.bedtime_reminders_summary, policy.reminders) { onChange(policy.copy(reminders = it)) } }
    item { SwitchRow(R.string.bedtime_events, R.string.bedtime_events_summary, policy.events) { onChange(policy.copy(events = it)) } }
}

@Composable
fun SwitchRow(@StringRes title: Int, @StringRes summary: Int?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

/**
 * How silenced notifications still show, as in Android's "Display options for filtered
 * notifications". Hiding them from the shade also hides their status bar icon and pop-up.
 */
fun LazyListScope.dndDisplayItems(policy: DndPolicy, onChange: (DndPolicy) -> Unit) {
    item {
        Text(
            stringResource(R.string.display_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    item { SectionHeader(R.string.display_screen_off) }
    item {
        DisplayRow(Icons.Outlined.ScreenLockPortrait, R.string.display_dont_turn_on, R.string.display_dont_turn_on_explain, policy.dontTurnOnScreen) {
            onChange(policy.copy(dontTurnOnScreen = it))
        }
    }
    item {
        DisplayRow(Icons.Outlined.Schedule, R.string.display_dont_wake, R.string.display_dont_wake_explain, policy.dontWake) {
            onChange(policy.copy(dontWake = it))
        }
    }
    item { SectionHeader(R.string.display_screen_on) }
    item {
        DisplayRow(Icons.Outlined.Apps, R.string.display_hide_badges, R.string.display_hide_badges_explain, policy.hideBadges) {
            onChange(policy.copy(hideBadges = it))
        }
    }
    item {
        DisplayRow(
            Icons.Outlined.NotificationsNone,
            R.string.display_hide_status_bar,
            if (policy.hideFromShade) R.string.display_implied_by_shade else R.string.display_hide_status_bar_explain,
            policy.effectiveHideStatusBarIcons,
            enabled = !policy.hideFromShade,
        ) { onChange(policy.copy(hideStatusBarIcons = it)) }
    }
    item {
        DisplayRow(
            Icons.Outlined.VerticalAlignTop,
            R.string.display_dont_pop,
            if (policy.hideFromShade) R.string.display_implied_by_shade else R.string.display_dont_pop_explain,
            policy.effectiveDontPop,
            enabled = !policy.hideFromShade,
        ) { onChange(policy.copy(dontPop = it)) }
    }
    item {
        DisplayRow(Icons.Outlined.KeyboardDoubleArrowDown, R.string.display_hide_from_shade, R.string.display_hide_from_shade_explain, policy.hideFromShade) {
            onChange(policy.copy(hideFromShade = it))
        }
    }
}

/** A display option: an icon for where it shows, what it does in plain words, and its switch. */
@Composable
private fun DisplayRow(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes explanation: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(explanation)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

internal val senderLabels = mapOf(
    Senders.ANYONE to R.string.senders_anyone,
    Senders.CONTACTS to R.string.senders_contacts,
    Senders.STARRED to R.string.senders_starred,
    Senders.NOBODY to R.string.senders_nobody,
)

/** "Calls from: Starred contacts", opening a choice of who can get through. */
@Composable
fun SendersRow(@StringRes title: Int, value: Senders, onChange: (Senders) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(senderLabels.getValue(value))) },
        modifier = Modifier.clickable { choosing = true },
    )
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Senders.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onChange(option)
                            choosing = false
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == value, onClick = null)
                        Text(stringResource(senderLabels.getValue(option)), modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.timer_dialog_cancel)) } },
    )
}

/** Android's Conversations page (priority ones first). Not in the public list of Settings pages, but present on Android 11+. */
private const val CONVERSATION_SETTINGS = "android.settings.CONVERSATION_SETTINGS"
