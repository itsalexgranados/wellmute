package com.wellmute.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.schedule.WeeklySchedule
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/** Switch, days and start/end times of a [WeeklySchedule]. Shared by Focus and Bedtime. */
@Composable
fun ScheduleEditor(schedule: WeeklySchedule, onChange: (WeeklySchedule) -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    val locale = LocalConfiguration.current.locales[0]

    ListItem(
        headlineContent = { Text(stringResource(R.string.focus_schedule_switch)) },
        trailingContent = { Switch(checked = schedule.enabled, onCheckedChange = { onChange(schedule.copy(enabled = it)) }) },
        modifier = Modifier.clickable { onChange(schedule.copy(enabled = !schedule.enabled)) },
    )
    if (!schedule.enabled) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DayOfWeek.entries.forEach { day ->
            DayToggle(
                letter = day.getDisplayName(TextStyle.NARROW, locale),
                fullName = day.getDisplayName(TextStyle.FULL, locale),
                selected = day in schedule.days,
                onToggle = { onChange(schedule.copy(days = if (day in schedule.days) schedule.days - day else schedule.days + day)) },
            )
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.focus_starts)) },
        trailingContent = { Text(formatMinute(schedule.startMinute), style = MaterialTheme.typography.titleMedium) },
        modifier = Modifier.clickable { editing = true },
    )
    ListItem(
        headlineContent = {
            Text(stringResource(if (schedule.endMinute <= schedule.startMinute) R.string.focus_ends_next_day else R.string.focus_ends))
        },
        trailingContent = { Text(formatMinute(schedule.endMinute), style = MaterialTheme.typography.titleMedium) },
        modifier = Modifier.clickable { editing = false },
    )

    editing?.let { isStart ->
        TimeDialog(
            initialMinute = if (isStart) schedule.startMinute else schedule.endMinute,
            onConfirm = { minute ->
                onChange(if (isStart) schedule.copy(startMinute = minute) else schedule.copy(endMinute = minute))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** One day of the week as a circle: filled when selected, outlined when not. */
@Composable
private fun DayToggle(letter: String, fullName: String, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outline, CircleShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = fullName },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = if (selected) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.focus_time_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timer_dialog_cancel)) } },
    )
}

fun formatMinute(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

