package com.wellmute.ui.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wellmute.R
import java.time.format.TextStyle
import com.wellmute.data.percentChange
import androidx.compose.ui.platform.LocalConfiguration
import com.wellmute.WellmuteApp
import com.wellmute.data.AppDay
import com.wellmute.data.AppTimer
import com.wellmute.data.PeriodType
import com.wellmute.data.WeekDay
import com.wellmute.apps.AppCategory
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.BarChart
import com.wellmute.ui.common.durationAxis
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.dayLabel
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.weekdayInitial
import com.wellmute.ui.timers.TimerDialog
import java.time.LocalDate

@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onOpenApp: (packageName: String, day: LocalDate, view: PeriodType?) -> Unit,
    onOpenFocusInsights: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val periodState by viewModel.periodState.collectAsStateWithLifecycle()
    var timerApp by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // The app list as one list or grouped by category, kept while switching views and dates.
    var byCategory by rememberSaveable { mutableStateOf(false) }
    // Category groups start folded, like a list of categories; tapping one opens it.
    var openCategories by remember { mutableStateOf(emptySet<AppCategory>()) }
    val toggleCategory: (AppCategory) -> Unit = { c -> openCategories = if (c in openCategories) openCategories - c else openCategories + c }
    val chosenCategories by container.categories.chosen.collectAsStateWithLifecycle()
    val categoryOf: (String) -> AppCategory = { container.categories.categoryOf(it, chosenCategories) }

    // "During Focus" opens Focus insights.
    CompositionLocalProvider(LocalOpenFocusInsights provides onOpenFocusInsights) {
        SubpageScaffold(title = R.string.dashboard_title, onBack = onBack) { padding ->
            val current = state ?: return@SubpageScaffold
            val summary = current.summary
            LazyColumn(contentPadding = padding) {
                if (!current.access.usage) {
                    item {
                        AccessCard(R.string.access_usage_message, R.string.access_grant) {
                            context.openSettings(container.permissions.usageAccessSettings(), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    }
                }

                item { ViewSelector(view, viewModel::showView) }

                val periodView = view
                if (periodView != null) {
                    val shown = periodState?.takeIf { it.summary.period.type == periodView }
                    if (shown != null) {
                        item {
                            DateHeader(
                                label = periodLabel(periodView, shown.summary.period.start),
                                canGoBack = shown.canGoBack,
                                canGoForward = shown.canGoForward,
                                onPrevious = viewModel::previousPeriod,
                                onNext = viewModel::nextPeriod,
                                onPick = { pickingDate = true },
                            )
                        }
                        periodItems(
                            summary = shown.summary,
                            selection = shown.selection,
                            showNotifications = current.access.notifications,
                            onBucketClick = viewModel::selectBucket,
                            byCategory = byCategory,
                            onByCategoryChange = { byCategory = it },
                            categoryOf = categoryOf,
                            openCategories = openCategories,
                            onToggleCategory = toggleCategory,
                        ) { app ->
                            // Open the app on the same period, around the tapped day or the last day shown.
                            val lastDay = minOf(shown.selection?.bucket?.end ?: shown.summary.period.end, LocalDate.now())
                            AppRow(
                                app = app,
                                timer = null,
                                isToday = false,
                                showNotifications = current.access.notifications,
                                onClick = { onOpenApp(app.packageName, lastDay, periodView) },
                                onTimerClick = null,
                            )
                        }
                    }
                    return@LazyColumn
                }

                item {
                    DateHeader(
                        label = dayLabel(summary.day),
                        canGoBack = current.canGoBack,
                        canGoForward = current.canGoForward,
                        onPrevious = viewModel::previousDay,
                        onNext = viewModel::nextDay,
                        onPick = { pickingDate = true },
                    )
                }

                item { DayOverview(summary, container.appInfo, current.access.notifications, R.string.dashboard_screen_time) }
                current.sameDayLastWeekMillis?.let { lastWeek ->
                    item { DayComparison(summary.totalMillis, lastWeek, summary.day) }
                }

                if (!current.access.notifications) {
                    item {
                        AccessCard(R.string.access_notifications_message, R.string.access_grant) {
                            context.openSettings(container.permissions.notificationAccessSettings(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                }

                item { WeekChart(current.week, summary.day) }
                item {
                    SessionStats(
                        listOf(summary.extras),
                        Modifier.padding(horizontal = 16.dp),
                        bedtimeTitle = if (summary.day == LocalDate.now()) R.string.after_bedtime_last_night else R.string.after_bedtime_previous_night,
                    )
                }

                item { SectionHeader(R.string.dashboard_apps) }
                if (summary.apps.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.dashboard_no_usage),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (summary.apps.isNotEmpty()) appListToggle(byCategory) { byCategory = it }
                appListItems(summary.apps, byCategory, categoryOf, openCategories, toggleCategory) { app ->
                    AppRow(
                        app = app,
                        timer = current.timers[app.packageName],
                        isToday = summary.day == LocalDate.now(),
                        showNotifications = current.access.notifications,
                        onClick = { onOpenApp(app.packageName, summary.day, null) },
                        // No timers on apps that could lock the user out (Settings, phone, Wellmute…).
                        onTimerClick = if (container.protectedApps.isProtected(app.packageName)) null else ({ timerApp = app.packageName }),
                    )
                }
            }

            if (pickingDate) {
                val shownPeriod = periodState?.summary?.period
                DayPickerDialog(
                    initial = shownPeriod?.let { minOf(it.end, LocalDate.now()) } ?: summary.day,
                    firstDay = current.firstDay,
                    onPick = { picked ->
                        pickingDate = false
                        val type = view
                        if (type == null) viewModel.select(picked) else viewModel.showPeriod(type, picked)
                    },
                    onDismiss = { pickingDate = false },
                )
            }

            timerApp?.let { packageName ->
                TimerDialog(
                    appLabel = container.appInfo.labelFor(packageName),
                    current = current.timers[packageName],
                    hasAccessibility = current.access.accessibility,
                    onOpenAccessibility = { context.openSettings(container.permissions.accessibilitySettings()) },
                    onSave = { limit ->
                        viewModel.saveTimer(packageName, limit)
                        timerApp = null
                    },
                    onDelete = {
                        viewModel.deleteTimer(packageName)
                        timerApp = null
                    },
                    onDismiss = { timerApp = null },
                )
            }
        }
    }
}

@Composable
private fun WeekChart(week: List<WeekDay>, selected: LocalDate) {
    // A tapped legend entry, picked out in every bar.
    var picked by remember { mutableStateOf<AppCategory?>(null) }
    val total = week.sumOf { it.totalMillis }
    val daysWithUsage = week.count { it.totalMillis > 0 }.coerceAtLeast(1)
    val totalText = formatDuration(total)
    val averageText = formatDuration(total / daysWithUsage)
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.dashboard_week_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.dashboard_week_summary, totalText, averageText),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        BarChart(
            values = week.map { it.totalMillis },
            labels = week.map { weekdayInitial(it.day) },
            description = stringResource(R.string.dashboard_week_summary, totalText, averageText),
            selectedIndex = week.indexOfFirst { it.day == selected }.takeIf { it >= 0 },
            average = (total / daysWithUsage).takeIf { total > 0 },
            axis = durationAxis(),
            stacks = categoryStacks(week.map { it.byCategory }),
            highlightedPart = picked?.ordinal,
            stackColors = categoryColors(),
        )
        val totals = AppCategory.entries.associateWith { c -> week.sumOf { it.byCategory[c] ?: 0L } }.filterValues { it > 0 }
        CategoryLegend(totals.keys, totals, highlighted = picked) { picked = if (picked == it) null else it }
    }
}

@Composable
private fun AppRow(
    app: AppDay,
    timer: AppTimer?,
    isToday: Boolean,
    showNotifications: Boolean,
    onClick: () -> Unit,
    onTimerClick: (() -> Unit)?,
) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    val details = buildList {
        add(formatDuration(app.foregroundMillis))
        if (showNotifications && app.notifications > 0) {
            add(pluralStringResource(R.plurals.notification_count, app.notifications, app.notifications))
        }
        if (app.opens > 0) add(pluralStringResource(R.plurals.open_count, app.opens, app.opens))
        if (timer != null) add(timerStatus(timer, app.foregroundMillis, isToday))
    }
    ListItem(
        headlineContent = { Text(container.appInfo.labelFor(app.packageName)) },
        supportingContent = { Text(details.joinToString(" · ")) },
        leadingContent = { AppIcon(app.packageName, container.appInfo) },
        trailingContent = onTimerClick?.let { onTimer ->
            {
                IconButton(onClick = onTimer) {
                    Icon(
                        if (timer != null) Icons.Filled.HourglassBottom else Icons.Outlined.HourglassEmpty,
                        contentDescription = stringResource(R.string.timer_set_description),
                        tint = if (timer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "20 min left" or "Paused" for today; the limit itself for past days. */
@Composable
fun timerStatus(timer: AppTimer, usedMillis: Long, isToday: Boolean): String {
    val left = timer.dailyLimitMillis - usedMillis
    return when {
        !isToday -> stringResource(R.string.timer_limit, formatDuration(timer.dailyLimitMillis))
        left <= 0 -> stringResource(R.string.timer_paused)
        // Rounded up to the minute, like the 5-minute warning bubble, so the two always agree
        // (2 min 30 s left is "3 min left", never "2 min" here and "3 min" in the bubble).
        else -> stringResource(R.string.timer_left, formatDuration((left + 59_999) / 60_000 * 60_000))
    }
}

@Composable
internal fun ViewSelector(view: PeriodType?, onSelect: (PeriodType?) -> Unit) {
    val options = listOf(null to R.string.view_day) + PeriodType.entries.map {
        it to when (it) {
            PeriodType.WEEK -> R.string.view_week
            PeriodType.MONTH -> R.string.view_month
            PeriodType.YEAR -> R.string.view_year
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = view == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

/** "‹ Today ›", where tapping the label opens a calendar. */
@Composable
internal fun DateHeader(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.dashboard_previous))
        }
        TextButton(onClick = onPick, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = stringResource(R.string.dashboard_pick_date),
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.dashboard_next))
        }
    }
}

/** A calendar limited to days we have history for, up to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(initial: LocalDate, firstDay: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val earliest = firstDay ?: today
    // The picker works in UTC midnights, which map one-to-one to calendar days.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * DAY_MILLIS,
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                LocalDate.ofEpochDay(utcTimeMillis / DAY_MILLIS) in earliest..today

            override fun isSelectableYear(year: Int): Boolean = year in earliest.year..today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MILLIS)) } ?: onDismiss() },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) {
        DatePicker(state, showModeToggle = false)
    }
}

private const val DAY_MILLIS = 86_400_000L

/** "12% less than last Thursday" (for today, "…by this time"), centred under the ring. */
@Composable
private fun DayComparison(totalMillis: Long, lastWeekMillis: Long, day: LocalDate) {
    val change = percentChange(lastWeekMillis, totalMillis) ?: return
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])
    val today = day == LocalDate.now()
    val text = when {
        change == 0 -> stringResource(if (today) R.string.day_compare_same_today else R.string.day_compare_same, weekday)
        change < 0 -> stringResource(if (today) R.string.day_compare_less_today else R.string.day_compare_less, -change, weekday)
        else -> stringResource(if (today) R.string.day_compare_more_today else R.string.day_compare_more, change, weekday)
    }
    // The same arrows as the week, month and year comparisons: up for more time, down for less.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (change != 0) {
            Icon(
                if (change < 0) Icons.Filled.ArrowCircleDown else Icons.Filled.ArrowCircleUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(20.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
