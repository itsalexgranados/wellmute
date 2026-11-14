package com.wellmute.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.data.Period
import com.wellmute.data.PeriodType
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.dayLabel
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.dashboard.DateHeader
import com.wellmute.ui.dashboard.DayPickerDialog
import com.wellmute.ui.dashboard.ViewSelector
import com.wellmute.ui.dashboard.periodLabel
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * How Focus went, by day, week, month or year: time in Focus and how much of it the phone wasn't
 * used, breaks, blocked attempts, and the apps used during it. Shows what it will hold until Focus
 * has been used.
 */
@Composable
fun FocusInsightsScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    val scope = rememberCoroutineScope()
    var dayEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var viewName by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val view = viewName?.let(PeriodType::valueOf)
    val day = LocalDate.ofEpochDay(dayEpoch)
    val firstDayOfWeek = WeekFields.of(LocalConfiguration.current.locales[0]).firstDayOfWeek
    val period = view?.let { Period.containing(it, day, firstDayOfWeek) }
    val from = period?.start ?: day
    val to = period?.end ?: day
    val firstDay by container.history.observeFirstDay().collectAsStateWithLifecycle(null)
    val insights by remember(from, to) { container.focusInsights.observe(from, to) }.collectAsStateWithLifecycle(null)
    val appInfo = container.appInfo

    // Today's apps and phone-free time are worked out when the day is saved: save it now.
    LifecycleResumeEffect(Unit) {
        scope.launch { container.history.syncDay(LocalDate.now()) }
        onPauseOrDispose { }
    }

    SubpageScaffold(title = R.string.focus_insights_title, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item { ViewSelector(view) { viewName = it?.name } }
            item {
                val today = LocalDate.now()
                DateHeader(
                    label = period?.let { periodLabel(it.type, it.start) } ?: dayLabel(day),
                    canGoBack = firstDay?.let { from > it } ?: false,
                    canGoForward = to < today,
                    onPrevious = { dayEpoch = (period?.previous()?.end ?: day.minusDays(1)).toEpochDay() },
                    onNext = { dayEpoch = minOf(period?.next()?.start ?: day.plusDays(1), today).toEpochDay() },
                    onPick = { picking = true },
                )
            }
            val current = insights ?: return@LazyColumn
            if (current.isEmpty) {
                item {
                    Text(
                        stringResource(R.string.focus_insights_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                return@LazyColumn
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.focus_insights_time), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(formatDuration(current.focusMillis), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
                    if (current.focusMillis > 0) {
                        val percent = (current.idleMillis * 100 / current.focusMillis).toInt()
                        Text(
                            stringResource(R.string.focus_insights_phone_free, percent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { InsightRow(stringResource(R.string.focus_insights_longest_free), formatDuration(current.longestIdleMillis)) }
            item {
                InsightRow(
                    stringResource(R.string.focus_insights_breaks),
                    if (current.breaks == 0) "0" else stringResource(R.string.focus_insights_breaks_value, current.breaks, formatDuration(current.breakMillis)),
                )
            }
            item { InsightRow(stringResource(R.string.focus_insights_breaks_early), current.breaksEndedEarly.toString()) }
            item { InsightRow(stringResource(R.string.focus_insights_blocked), current.blocked.toString()) }

            if (current.apps.isNotEmpty()) {
                item { SectionHeader(R.string.focus_insights_apps) }
                items(current.apps, key = { "used-${it.packageName}" }) { app ->
                    ListItem(
                        headlineContent = { Text(appInfo.labelFor(app.packageName)) },
                        supportingContent = {
                            Text(formatDuration(app.millis) + ", " + pluralStringResource(R.plurals.open_count, app.opens, app.opens))
                        },
                        leadingContent = { AppIcon(app.packageName, appInfo) },
                    )
                }
            }
            if (current.blockedApps.isNotEmpty()) {
                item { SectionHeader(R.string.focus_insights_blocked_apps) }
                items(current.blockedApps, key = { "blocked-${it.first}" }) { (pkg, times) ->
                    ListItem(
                        headlineContent = { Text(appInfo.labelFor(pkg)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.focus_insights_times, times, times)) },
                        leadingContent = { AppIcon(pkg, appInfo) },
                    )
                }
            }
        }

        if (picking) {
            DayPickerDialog(
                initial = minOf(to, LocalDate.now()),
                firstDay = firstDay,
                onPick = {
                    picking = false
                    dayEpoch = it.toEpochDay()
                },
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun InsightRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
    }
}
