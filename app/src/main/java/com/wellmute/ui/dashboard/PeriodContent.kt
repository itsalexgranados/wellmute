package com.wellmute.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.wellmute.R
import com.wellmute.apps.AppCategory
import com.wellmute.data.AppDay
import com.wellmute.data.Bucket
import com.wellmute.data.PeriodSummary
import com.wellmute.data.PeriodType
import com.wellmute.ui.common.BarChart
import com.wellmute.ui.common.durationAxis
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.weekdayInitial
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The week, month or year view: daily average, trend, chart, then every app's totals. */
fun LazyListScope.periodItems(
    summary: PeriodSummary,
    selection: DashboardViewModel.Selection?,
    showNotifications: Boolean,
    onBucketClick: (Bucket?) -> Unit,
    byCategory: Boolean,
    onByCategoryChange: (Boolean) -> Unit,
    categoryOf: (String) -> AppCategory,
    openCategories: Set<AppCategory>,
    onToggleCategory: (AppCategory) -> Unit,
    appRow: @Composable (AppDay) -> Unit,
) {
    item { PeriodOverview(summary, selection, showNotifications, onBucketClick) }
    item { SectionHeader(R.string.dashboard_apps) }
    val apps = (selection?.apps ?: summary.apps).filter { it.foregroundMillis > 0 || it.notifications > 0 }
    if (apps.isEmpty()) {
        item {
            Text(
                stringResource(R.string.period_no_usage),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (apps.isNotEmpty()) appListToggle(byCategory, onByCategoryChange)
    appListItems(apps, byCategory, categoryOf, openCategories, onToggleCategory, appRow)
}

@Composable
private fun PeriodOverview(
    summary: PeriodSummary,
    selection: DashboardViewModel.Selection?,
    showNotifications: Boolean,
    onBucketClick: (Bucket?) -> Unit,
) {
    val period = summary.period
    // A tapped legend entry, picked out in every bar; forgotten when the period changes.
    var picked by remember(period) { mutableStateOf<AppCategory?>(null) }
    val buckets = period.buckets()
    val average = formatDuration(summary.averageMillis)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        // Both headers are always laid out, one on top of the other, and only one is shown: the
        // space they take never changes, so tapping a bar doesn't make the chart and list jump.
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            val periodShown = selection == null
            Column(Modifier.alpha(if (periodShown) 1f else 0f).hiddenFromAccessibility(!periodShown)) {
                // Exactly as tall as the other header's row with the "Whole week" button, so the lines align.
                Box(Modifier.height(TITLE_ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.period_daily_average), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                // The comparison sits beside the average, so it never adds a line.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(average, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (summary.countedDays > 0) {
                        TrendBadge(summary.previousAverageMillis, summary.averageMillis, period.type, period.start, Modifier.padding(start = 12.dp))
                    }
                }
                Text(
                    stringResource(R.string.period_total, formatDuration(summary.totalMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The tapped day (or month) replaces the period's numbers until it's tapped again.
            Column(Modifier.alpha(if (periodShown) 0f else 1f).hiddenFromAccessibility(periodShown)) {
                Row(Modifier.height(TITLE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selection?.let { bucketLabel(period.type, it.bucket) }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!periodShown) {
                        TextButton(onClick = { onBucketClick(null) }) {
                            Text(stringResource(wholePeriodLabel(period.type)))
                        }
                    }
                }
                Text(selection?.let { formatDuration(it.totalMillis) }.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (period.type) {
                        PeriodType.WEEK -> stringResource(R.string.period_week_averages, average)
                        PeriodType.MONTH -> stringResource(R.string.period_month_averages, average)
                        PeriodType.YEAR -> stringResource(R.string.period_this_month_average, formatDuration(selection?.averageMillis ?: 0))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BarChart(
            values = summary.bars,
            labels = buckets.map { chartLabel(period.type, it.start) },
            description = stringResource(
                if (period.type == PeriodType.YEAR) R.string.period_chart_months else R.string.period_chart_days,
                average,
            ),
            selectedIndex = selection?.let { buckets.indexOf(it.bucket) }?.takeIf { it >= 0 },
            // Bars after today have nothing to show.
            onBarClick = { index -> buckets[index].takeIf { it.start <= LocalDate.now() }?.let(onBucketClick) },
            average = summary.averageMillis.takeIf { summary.countedDays > 0 },
            axis = durationAxis(),
            stacks = categoryStacks(summary.categoryBars),
            highlightedPart = picked?.ordinal,
            stackColors = categoryColors(),
        )
        // The tapped bar's categories (a month's daily average, in a year), or the whole period's.
        val selectedIndex = selection?.let { buckets.indexOf(it.bucket) }?.takeIf { it >= 0 }
        CategoryLegend(
            summary.categoryTotals.keys,
            selectedIndex?.let { summary.categoryBars.getOrNull(it) } ?: summary.categoryTotals,
            highlighted = picked,
        ) { picked = if (picked == it) null else it }
        val unlocks = selection?.unlocks ?: summary.unlocks
        val notifications = selection?.totalNotifications ?: summary.totalNotifications
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(unlocks.toString(), R.string.home_unlocks)
            Stat(if (showNotifications) notifications.toString() else "–", R.string.home_notifications)
        }
        val range = selection?.bucket?.let { it.start..it.end } ?: period.start..period.end
        // Bedtime and Focus rows show for the whole period whenever any day has them, so tapping a
        // bar never adds or removes a row.
        SessionStats(
            days = summary.extras.filterKeys { it in range }.values,
            singleDay = selection != null && period.type != PeriodType.YEAR,
            showBedtime = summary.extras.values.any { it.afterBedtimeMillis != null },
            showFocus = summary.extras.values.any { it.duringFocusMillis != null },
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** "Thursday, 5 November" for a day, "March 2026" for a month. */
internal fun bucketLabel(type: PeriodType, bucket: Bucket): String = when (type) {
    PeriodType.WEEK, PeriodType.MONTH -> bucket.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    PeriodType.YEAR -> bucket.start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
}

internal fun wholePeriodLabel(type: PeriodType): Int = when (type) {
    PeriodType.WEEK -> R.string.period_show_week
    PeriodType.MONTH -> R.string.period_show_month
    PeriodType.YEAR -> R.string.period_show_year
}

/** Weekday initials for weeks, every 7th day number for months, month initials for years. */
internal fun chartLabel(type: PeriodType, start: LocalDate): String = when (type) {
    PeriodType.WEEK -> weekdayInitial(start)
    PeriodType.MONTH -> if ((start.dayOfMonth - 1) % 7 == 0) start.dayOfMonth.toString() else ""
    PeriodType.YEAR -> start.format(DateTimeFormatter.ofPattern("LLLLL"))
}

/** "This week", "Last month", "March 2026", "2025"… */
@Composable
fun periodLabel(type: PeriodType, start: LocalDate): String {
    val today = LocalDate.now()
    return when (type) {
        PeriodType.WEEK -> {
            val thisWeekStart = today.minusDays(((today.dayOfWeek.value - start.dayOfWeek.value + 7) % 7).toLong())
            when (start) {
                thisWeekStart -> stringResource(R.string.period_this_week)
                thisWeekStart.minusWeeks(1) -> stringResource(R.string.period_last_week)
                else -> stringResource(R.string.period_week_of, start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            }
        }
        PeriodType.MONTH -> when (start) {
            today.withDayOfMonth(1) -> stringResource(R.string.period_this_month)
            today.withDayOfMonth(1).minusMonths(1) -> stringResource(R.string.period_last_month)
            else -> start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
        }
        PeriodType.YEAR -> when (start.year) {
            today.year -> stringResource(R.string.period_this_year)
            today.year - 1 -> stringResource(R.string.period_last_year)
            else -> start.year.toString()
        }
    }
}

/** Keeps a header that is laid out but not shown from being read by screen readers. */
internal fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier =
    if (hidden) clearAndSetSemantics { } else this

/** The header's first row: the tallest thing in it is the "Whole week" button (48dp touch target). */
internal val TITLE_ROW_HEIGHT = 48.dp
