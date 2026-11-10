package com.wellmute.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wellmute.R
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import com.wellmute.reminders.LastReminder
import com.wellmute.reminders.ReminderBubble
import androidx.compose.ui.res.colorResource
import com.wellmute.WellmuteApp
import com.wellmute.reminders.ReminderSettings
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.SwitchRow
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.openSettings
import kotlinx.coroutines.launch

@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val reminders = container.usageReminders
    val settings by reminders.settings.collectAsStateWithLifecycle()
    val last by reminders.lastReminder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-read access whenever the screen comes back from Settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val hasAccessibility = remember(resumes) { container.permissions.hasAccessibility() }

    SubpageScaffold(title = R.string.reminders_title, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.reminders_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (settings.enabled && !hasAccessibility) {
                item {
                    AccessCard(R.string.reminders_needs_accessibility, R.string.timer_open_accessibility) {
                        context.openSettings(container.permissions.accessibilitySettings())
                    }
                }
            }
            item {
                SwitchRow(R.string.reminders_switch, R.string.reminders_switch_summary, settings.enabled) { on ->
                    scope.launch { reminders.setEnabled(on) }
                }
            }
            item { BubblePreview(settings.intervalMinutes) }
            if (settings.enabled) {
                item { SectionHeader(R.string.reminders_interval) }
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReminderSettings.INTERVALS.forEach { minutes ->
                            FilterChip(
                                selected = settings.intervalMinutes == minutes,
                                onClick = { scope.launch { reminders.setInterval(minutes) } },
                                label = { Text(formatDuration(minutes * 60_000L)) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.reminders_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item { LastReminderStatus(last) }
            }
        }
    }
}

/**
 * What a reminder looks like, drawn like the real bubble (ReminderBubble): dark, tinted with the
 * wallpaper's colours.
 */
@Composable
private fun BubblePreview(minutes: Int) {
    val text = stringResource(R.string.reminders_bubble, stringResource(R.string.reminders_preview_app), formatDuration(minutes * 60_000L))
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.reminders_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // A stand-in for "some app": the bubble floats over whatever is open.
        Box(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 20.dp)
                .semantics(mergeDescendants = true) {},
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                Modifier
                    .shadow(2.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(colorResource(ReminderBubble.BACKGROUND))
                    .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = colorResource(ReminderBubble.ICON),
                    modifier = Modifier.size(20.dp).scale(2.2f),
                )
                Text(
                    text,
                    color = colorResource(ReminderBubble.TEXT),
                    // The same size as the real bubble's text (15sp).
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

/**
 * When the last reminder fired, so it's clear whether they work at all: "Last reminder: Calculator,
 * 10 minutes, at 14:05", or a note if the bubble couldn't be drawn.
 */
@Composable
private fun LastReminderStatus(last: LastReminder?) {
    val text = if (last == null) {
        stringResource(R.string.reminders_none_yet)
    } else {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(last.atMillis).atZone(ZoneId.systemDefault()))
        val line = pluralStringResource(R.plurals.reminders_last, last.minutes, last.appLabel, last.minutes, time)
        if (last.bubbleShown) line else "$line\n${stringResource(R.string.reminders_not_shown)}"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
