package com.wellmute.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.wellmute.R
import com.wellmute.modes.DndPolicy
import com.wellmute.schedule.WeeklySchedule
import java.time.format.TextStyle

/** A row that opens a sub-page, showing what's chosen there ("Schedule · 22:00–07:00 · Mon, Tue"). */
@Composable
fun NavRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** "22:00–07:00 · Mon, Tue, Wed" or "Manual only". */
@Composable
fun scheduleSummary(times: WeeklySchedule): String {
    if (!times.enabled) return stringResource(R.string.dnd_manual_only)
    val locale = LocalConfiguration.current.locales[0]
    return "${formatMinute(times.startMinute)}–${formatMinute(times.endMinute)} · " +
        if (times.days.size == 7) {
            stringResource(R.string.summary_every_day)
        } else {
            times.days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        }
}

/** "Calls and messages: Starred contacts only", or both separately when they differ. */
@Composable
fun peopleSummary(policy: DndPolicy): String {
    val calls = stringResource(senderLabels.getValue(policy.callsFrom))
    val messages = stringResource(senderLabels.getValue(policy.messagesFrom))
    return if (policy.callsFrom == policy.messagesFrom) {
        stringResource(R.string.summary_people_same, calls)
    } else {
        stringResource(R.string.summary_people, calls, messages)
    }
}

/** "Alarms, Media", or "No sounds". */
@Composable
fun soundsSummary(policy: DndPolicy): String {
    val names = buildList {
        if (policy.alarms) add(stringResource(R.string.bedtime_alarms))
        if (policy.media) add(stringResource(R.string.bedtime_media))
        if (policy.touchSounds) add(stringResource(R.string.bedtime_touch_sounds))
        if (policy.reminders) add(stringResource(R.string.bedtime_reminders))
        if (policy.events) add(stringResource(R.string.bedtime_events))
    }
    return names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.summary_no_sounds)
}

/** "Hidden from the shade", "No pop-ups, screen stays off"… or "Shown as usual, without sound". */
@Composable
fun displaySummary(policy: DndPolicy): String {
    if (policy.hideFromShade) return stringResource(R.string.display_summary_hidden)
    val parts = buildList {
        if (policy.effectiveDontPop) add(stringResource(R.string.display_summary_no_pop))
        if (policy.dontTurnOnScreen) add(stringResource(R.string.display_summary_screen_off))
        if (policy.hideBadges) add(stringResource(R.string.display_summary_no_dots))
        if (policy.effectiveHideStatusBarIcons) add(stringResource(R.string.display_summary_no_icons))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.display_summary_shown)
}
