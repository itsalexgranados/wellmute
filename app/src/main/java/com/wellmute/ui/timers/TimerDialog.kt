package com.wellmute.ui.timers

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.data.AppTimer
import com.wellmute.ui.common.formatDuration

private data class Preset(val minutes: Int, @StringRes val label: Int)

private val presets = listOf(
    Preset(20, R.string.timer_preset_20m),
    Preset(30, R.string.timer_preset_30m),
    Preset(60, R.string.timer_preset_1h),
    Preset(120, R.string.timer_preset_2h),
)

/** Sets, changes or deletes the daily timer of one app. */
@Composable
fun TimerDialog(
    appLabel: String,
    current: AppTimer?,
    hasAccessibility: Boolean,
    onOpenAccessibility: () -> Unit,
    onSave: (dailyLimitMillis: Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableIntStateOf(current?.let { (it.dailyLimitMillis / 60_000).toInt() } ?: DEFAULT_MINUTES) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_dialog_title, appLabel)) },
        text = {
            Column {
                Text(stringResource(R.string.timer_dialog_limit), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = { minutes = stepDown(minutes) }, enabled = minutes > MIN_MINUTES) {
                        Icon(Icons.Filled.Remove, stringResource(R.string.timer_dialog_less))
                    }
                    Text(
                        formatDuration(minutes * 60_000L),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    FilledTonalIconButton(onClick = { minutes = stepUp(minutes) }, enabled = minutes < MAX_MINUTES) {
                        Icon(Icons.Filled.Add, stringResource(R.string.timer_dialog_more))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = minutes == preset.minutes,
                            onClick = { minutes = preset.minutes },
                            label = { Text(stringResource(preset.label)) },
                        )
                    }
                }
                if (!hasAccessibility) {
                    Text(
                        stringResource(R.string.timer_needs_accessibility),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = onOpenAccessibility) { Text(stringResource(R.string.timer_open_accessibility)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(minutes * 60_000L)
                },
            ) { Text(stringResource(R.string.timer_dialog_save)) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.timer_dialog_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.timer_dialog_cancel)) }
            }
        },
    )
}

/** One minute at a time, for any limit; the presets are there for the big jumps. */
private fun stepUp(minutes: Int): Int = minOf(minutes + 1, MAX_MINUTES)

private fun stepDown(minutes: Int): Int = maxOf(minutes - 1, MIN_MINUTES)

private const val DEFAULT_MINUTES = 30
private const val MIN_MINUTES = 1
private const val MAX_MINUTES = 23 * 60 + 59
