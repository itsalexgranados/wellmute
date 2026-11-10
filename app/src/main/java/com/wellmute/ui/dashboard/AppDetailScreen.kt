package com.wellmute.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.wellmute.apps.AppCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.data.Metric
import com.wellmute.data.PeriodType
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.BarChart
import com.wellmute.ui.common.countAxis
import com.wellmute.ui.common.durationAxis
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.dayLabel
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.timers.TimerDialog
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val metrics = listOf(
    Metric.SCREEN_TIME to R.string.metric_screen_time,
    Metric.NOTIFICATIONS to R.string.metric_notifications,
    Metric.OPENS to R.string.metric_opens,
)

@Composable
fun AppDetailScreen(onBack: () -> Unit, viewModel: AppDetailViewModel = viewModel(factory = AppDetailViewModel.Factory)) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showTimerDialog by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    val chosenCategories by container.categories.chosen.collectAsStateWithLifecycle()

    if (showTimerDialog) {
        TimerDialog(
            appLabel = container.appInfo.labelFor(viewModel.packageName),
            current = timer,
            hasAccessibility = container.permissions.hasAccessibility(),
            onOpenAccessibility = { context.openSettings(container.permissions.accessibilitySettings()) },
            onSave = { limit ->
                viewModel.saveTimer(limit)
                showTimerDialog = false
            },
            onDelete = {
                viewModel.deleteTimer()
                showTimerDialog = false
            },
            onDismiss = { showTimerDialog = false },
        )
    }

    SubpageScaffold(title = R.string.app_detail_title, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(viewModel.packageName, container.appInfo, size = 48.dp)
                Text(
                    container.appInfo.labelFor(viewModel.packageName),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            // No timers on apps that could lock the user out (Settings, phone, Wellmute…).
            if (!container.protectedApps.isProtected(viewModel.packageName)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_detail_timer)) },
                    supportingContent = {
                        Text(timer?.let { stringResource(R.string.timer_limit, formatDuration(it.dailyLimitMillis)) } ?: stringResource(R.string.app_detail_no_timer))
                    },
                    leadingContent = { Icon(if (timer != null) Icons.Filled.HourglassBottom else Icons.Outlined.HourglassEmpty, contentDescription = null) },
                    modifier = Modifier.clickable { showTimerDialog = true },
                )
            }

            // Which kind of app it is, for the Dashboard's split by category; changeable.
            val category = container.categories.categoryOf(viewModel.packageName, chosenCategories)
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_detail_category)) },
                supportingContent = { Text(stringResource(category.label)) },
                leadingContent = { Icon(Icons.Outlined.Category, contentDescription = null) },
                modifier = Modifier.clickable { pickingCategory = true },
            )
            if (pickingCategory) {
                CategoryDialog(
                    chosen = chosenCategories[viewModel.packageName],
                    automatic = container.categories.automaticCategory(viewModel.packageName),
                    onPick = { picked ->
                        pickingCategory = false
                        scope.launch { container.categories.choose(viewModel.packageName, picked) }
                    },
                    onDismiss = { pickingCategory = false },
                )
            }

            ViewSelector(view, viewModel::showView)

            // Only draw what belongs to the view picked, so switching never flashes the old one.
            val current = state?.takeIf { it.period?.type == view } ?: return@Column

            DateHeader(
                label = current.period?.let { periodLabel(it.type, it.start) } ?: dayLabel(current.day),
                canGoBack = current.canGoBack,
                canGoForward = current.canGoForward,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onPick = { pickingDate = true },
            )

            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { (metric, label) ->
                    FilterChip(
                        selected = current.metric == metric,
                        onClick = { viewModel.selectMetric(metric) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            Overview(current, onBarClick = viewModel::selectBar)

            if (pickingDate) {
                DayPickerDialog(
                    initial = current.period?.let { minOf(it.end, LocalDate.now()) } ?: current.day,
                    firstDay = current.firstDay,
                    onPick = { picked ->
                        pickingDate = false
                        val type = current.period?.type
                        if (type == null) viewModel.selectDay(picked) else viewModel.showPeriod(type, picked)
                    },
                    onDismiss = { pickingDate = false },
                )
            }
        }
    }
}

/**
 * The numbers and the chart, like the dashboard's: the day's total or the period's daily average,
 * and a tapped bar's value in its place, both laid out so nothing moves when a bar is tapped.
 */
@Composable
private fun Overview(state: AppDetailViewModel.State, onBarClick: (Int?) -> Unit) {
    val period = state.period
    val buckets = period?.buckets()
    val metric = state.metric
    val today = LocalDate.now()
    val index = state.selectedIndex
    val selectedValue = index?.let { state.bars[it] }
    val average = state.dailyAverage?.let { formatMetric(metric, it) }

    val labels = if (period == null || buckets == null) {
        // Like a clock: 0, 6, 12, 18.
        state.bars.indices.map { if (it % 6 == 0) it.toString() else "" }
    } else {
        buckets.map { chartLabel(period.type, it.start) }
    }
    val barNames = if (period == null || buckets == null) state.bars.indices.map(::hourLabel) else buckets.map { bucketLabel(period.type, it) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            val wholeShown = index == null
            Column(Modifier.alpha(if (wholeShown) 1f else 0f).hiddenFromAccessibility(!wholeShown)) {
                Box(Modifier.height(TITLE_ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(
                        stringResource(if (period == null) metrics.first { it.first == metric }.second else R.string.period_daily_average),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // Like the dashboard: the comparison sits beside the number, so it never adds a line.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(average ?: formatMetric(metric, state.total), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TrendBadge(
                        previous = state.previous,
                        current = state.dailyAverage ?: state.total,
                        type = period?.type,
                        day = state.day,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Text(
                    if (period == null) "" else stringResource(R.string.period_total, formatMetric(metric, state.total)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The tapped hour, day or month replaces the whole thing until it's tapped again.
            Column(Modifier.alpha(if (wholeShown) 0f else 1f).hiddenFromAccessibility(wholeShown)) {
                Row(Modifier.height(TITLE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        index?.let { barNames[it] }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!wholeShown) {
                        TextButton(onClick = { onBarClick(null) }) {
                            Text(stringResource(period?.let { wholePeriodLabel(it.type) } ?: R.string.app_detail_show_day))
                        }
                    }
                }
                Text(selectedValue?.let { formatMetric(metric, it) }.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (period?.type) {
                        null -> stringResource(R.string.app_detail_day_total, formatMetric(metric, state.total))
                        PeriodType.WEEK -> stringResource(R.string.period_week_averages, average.orEmpty())
                        PeriodType.MONTH -> stringResource(R.string.period_month_averages, average.orEmpty())
                        PeriodType.YEAR -> stringResource(R.string.app_detail_year_averages, average.orEmpty())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val values = state.bars.map { formatMetric(metric, it) }
        BarChart(
            values = state.bars,
            labels = labels,
            description = barNames.zip(values).filterIndexed { i, _ -> state.bars[i] > 0 }.joinToString { (name, value) -> "$name: $value" },
            selectedIndex = index,
            onBarClick = { i ->
                // Hours and days still to come have nothing to show.
                val future = if (buckets == null) state.day == today && i > LocalTime.now().hour else buckets[i].start > today
                if (!future) onBarClick(i)
            },
            average = state.dailyAverage?.takeIf { period != null },
            axis = if (metric == Metric.SCREEN_TIME) durationAxis() else countAxis(),
        )
    }
}

/** "14:00", in the phone's own time format. */
private fun hourLabel(hour: Int): String =
    LocalTime.of(hour % 24, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
private fun formatMetric(metric: Metric, value: Long): String = when (metric) {
    Metric.SCREEN_TIME -> formatDuration(value)
    Metric.NOTIFICATIONS -> pluralStringResource(R.plurals.notification_count, value.toInt(), value.toInt())
    Metric.OPENS -> pluralStringResource(R.plurals.open_count, value.toInt(), value.toInt())
}

/** Automatic, or one of the categories; the automatic one says what it would be. */
@Composable
private fun CategoryDialog(chosen: AppCategory?, automatic: AppCategory, onPick: (AppCategory?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf<AppCategory?>(null) + AppCategory.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_detail_category)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.app_detail_category_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == option, role = Role.RadioButton) { onPick(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == option, onClick = null)
                        Text(
                            option?.let { stringResource(it.label) } ?: stringResource(R.string.app_detail_category_automatic, stringResource(automatic.label)),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
