package com.wellmute.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wellmute.R
import com.wellmute.ui.common.appSearchItem
import com.wellmute.ui.common.matchesSearch
import com.wellmute.WellmuteApp
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.ScheduleEditor
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.NavRow
import com.wellmute.ui.common.SwitchRow
import com.wellmute.ui.common.SubpageScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val breakLengths = listOf(5, 15, 30)

@Composable
fun FocusScreen(onBack: () -> Unit, onOpenInsights: () -> Unit, viewModel: FocusViewModel = viewModel(factory = FocusViewModel.Factory)) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    SubpageScaffold(title = R.string.focus_title, onBack = onBack) { padding ->
        val current = state ?: return@SubpageScaffold
        val settings = current.settings
        LazyColumn(state = listState, contentPadding = padding) {
            item { StatusCard(current, viewModel) }
            // How Focus went: time in Focus, phone-free time, breaks, blocked attempts, apps used.
            item { NavRow(stringResource(R.string.focus_insights_title), stringResource(R.string.focus_insights_summary), onClick = onOpenInsights) }

            if (!current.hasAccessibility) {
                item {
                    AccessCard(R.string.focus_needs_accessibility, R.string.timer_open_accessibility) {
                        context.openSettings(container.permissions.accessibilitySettings())
                    }
                }
            }

            item { SectionHeader(R.string.focus_schedule) }
            item { ScheduleEditor(settings.schedule, viewModel::setSchedule) }

            item { SectionHeader(R.string.focus_breaks) }
            item {
                SwitchRow(R.string.focus_break_warning, R.string.focus_break_warning_summary, settings.warnBeforeBreakEnds) {
                    viewModel.setWarnBeforeBreakEnds(it)
                }
            }

            item { SectionHeader(R.string.focus_apps) }
            item {
                Text(
                    stringResource(R.string.focus_apps_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            appSearchItem(query, { query = it }, listState)
            items(current.apps.filter { matchesSearch(it.label, query) }, key = { it.packageName }) { app ->
                val checked = app.packageName in settings.apps
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIcon(app.packageName, container.appInfo) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = { viewModel.setApp(app.packageName, it) }) },
                    modifier = Modifier.clickable { viewModel.setApp(app.packageName, !checked) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: FocusViewModel.State, viewModel: FocusViewModel) {
    val settings = state.settings
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when {
                state.isOnBreak -> stringResource(R.string.focus_on_break, formatTime(Instant.ofEpochMilli(settings.breakUntil)))
                state.isActive -> stringResource(R.string.focus_on)
                else -> stringResource(R.string.focus_off)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    settings.apps.isEmpty() -> stringResource(R.string.focus_no_apps)
                    state.isActive -> pluralStringResource(R.plurals.focus_on_summary, settings.apps.size, settings.apps.size)
                    else -> stringResource(R.string.focus_off_summary)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state.isOnBreak -> Button(onClick = viewModel::endBreak) { Text(stringResource(R.string.focus_end_break)) }
                state.isActive -> {
                    OutlinedButton(onClick = viewModel::turnOff) { Text(stringResource(R.string.focus_turn_off)) }
                    Text(stringResource(R.string.focus_take_break), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        breakLengths.forEach { minutes ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.takeBreak(minutes) },
                                label = { Text(stringResource(R.string.focus_break_minutes, minutes)) },
                            )
                        }
                    }
                }
                else -> Button(onClick = viewModel::turnOn) { Text(stringResource(R.string.focus_turn_on)) }
            }
        }
    }
}

private fun formatTime(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
