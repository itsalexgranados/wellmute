package com.wellmute.ui.timers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppChoice
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.SwitchRow
import com.wellmute.ui.common.appSearchItem
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.matchesSearch
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.dashboard.timerStatus

/** Every app with a timer and how much of it is left today, plus "Add timer". */
@Composable
fun AppTimersScreen(onBack: () -> Unit, onAdd: () -> Unit, viewModel: AppTimersViewModel = viewModel(factory = AppTimersViewModel.Factory)) {
    val context = LocalContext.current
    val appInfo = (context.applicationContext as WellmuteApp).container.appInfo
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AppTimersViewModel.TimedApp?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SubpageScaffold(title = R.string.timers_title, onBack = onBack) { padding ->
        val current = state ?: return@SubpageScaffold
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.timers_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (!current.hasAccessibility) {
                item {
                    AccessCard(R.string.timer_needs_accessibility, R.string.timer_open_accessibility) {
                        context.openSettings(viewModel.accessibilitySettings())
                    }
                }
            }
            item {
                SwitchRow(R.string.timers_warn, R.string.timers_warn_summary, current.warnBeforeEnd, onChange = viewModel::setWarnBeforeEnd)
            }
            if (current.timed.isNotEmpty()) item { SectionHeader(R.string.timers_section) }
            items(current.timed, key = { it.timer.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.timer_limit, formatDuration(app.timer.dailyLimitMillis)) +
                                " · " + timerStatus(app.timer, app.usedTodayMillis, isToday = true),
                        )
                    },
                    leadingContent = { AppIcon(app.timer.packageName, appInfo) },
                    modifier = Modifier.clickable { editing = app },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.timers_add), color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(onClick = onAdd),
                )
            }
        }

        editing?.let { app ->
            TimerDialog(
                appLabel = app.label,
                current = app.timer,
                hasAccessibility = current.hasAccessibility,
                onOpenAccessibility = { context.openSettings(viewModel.accessibilitySettings()) },
                onSave = { limit ->
                    viewModel.save(app.timer.packageName, limit)
                    editing = null
                },
                onDelete = {
                    viewModel.delete(app.timer.packageName)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/** Pick any app, with search, then set its timer; saving goes back to the list. */
@Composable
fun AddTimerScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: AppTimersViewModel = viewModel(factory = AppTimersViewModel.Factory)) {
    val context = LocalContext.current
    val appInfo = (context.applicationContext as WellmuteApp).container.appInfo
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<AppChoice?>(null) }
    val listState = rememberLazyListState()

    SubpageScaffold(title = R.string.timers_add_title, onBack = onBack) { padding ->
        val current = state ?: return@SubpageScaffold
        LazyColumn(state = listState, contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.timers_add_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            appSearchItem(query, { query = it }, listState)
            items(current.addable.filter { matchesSearch(it.label, query) }, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIcon(app.packageName, appInfo) },
                    modifier = Modifier.clickable { picked = app },
                )
            }
        }

        picked?.let { app ->
            TimerDialog(
                appLabel = app.label,
                current = null,
                hasAccessibility = current.hasAccessibility,
                onOpenAccessibility = { context.openSettings(viewModel.accessibilitySettings()) },
                onSave = { limit ->
                    viewModel.save(app.packageName, limit)
                    picked = null
                    onDone()
                },
                onDelete = { picked = null },
                onDismiss = { picked = null },
            )
        }
    }
}
