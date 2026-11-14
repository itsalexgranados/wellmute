package com.wellmute.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppCategory
import com.wellmute.data.AppDay
import com.wellmute.data.DayExtras
import com.wellmute.ui.common.formatDuration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One colour per category, in [AppCategory] order, for stacked bars and their legend. Fixed hues
 * far apart from each other (blue, pink, green, amber, grey), lighter in the dark theme, so no two
 * categories can be mistaken whatever the wallpaper's colours.
 */
@Composable
fun categoryColors(): List<Color> {
    val dark = isSystemInDarkTheme()
    return AppCategory.entries.map {
        when (it) {
            AppCategory.SOCIAL -> if (dark) Color(0xFF5EA8FF) else Color(0xFF1565C0)
            AppCategory.ENTERTAINMENT -> if (dark) Color(0xFFFF7A9C) else Color(0xFFC2185B)
            AppCategory.GAMES -> if (dark) Color(0xFF6DD58C) else Color(0xFF2E7D32)
            AppCategory.PRODUCTIVITY -> if (dark) Color(0xFFFFC15E) else Color(0xFFE65100)
            AppCategory.OTHER -> if (dark) Color(0xFF7D7F87) else Color(0xFF9E9EA6)
        }
    }
}

fun categoryIcon(category: AppCategory): ImageVector = when (category) {
    AppCategory.SOCIAL -> Icons.Outlined.Forum
    AppCategory.ENTERTAINMENT -> Icons.Outlined.Movie
    AppCategory.GAMES -> Icons.Outlined.SportsEsports
    AppCategory.PRODUCTIVITY -> Icons.Outlined.WorkOutline
    AppCategory.OTHER -> Icons.Outlined.Apps
}

/** Each bar's parts in [AppCategory] order, bottom first. */
fun categoryStacks(bars: List<Map<AppCategory, Long>>): List<List<Long>> =
    bars.map { bar -> AppCategory.entries.map { bar[it] ?: 0L } }

/**
 * Every category in [shown] with its colour and its time in [totals], three to a row. [shown]
 * should stay the same while bars are tapped (the categories of the whole period), so the legend
 * never changes size or order; a category with nothing in [totals] shows "0 min".
 */
@Composable
fun CategoryLegend(
    shown: Set<AppCategory>,
    totals: Map<AppCategory, Long>,
    modifier: Modifier = Modifier,
    /** The category picked out in the bars, shown selected here; the rest fade. */
    highlighted: AppCategory? = null,
    /** Tapping an entry picks it out in the bars; tapping it again goes back to all of them. */
    onCategoryClick: ((AppCategory) -> Unit)? = null,
) {
    if (shown.isEmpty()) return
    val colors = categoryColors()
    // The same order as the parts of each bar, bottom first.
    val ordered = shown.sortedBy { it.ordinal }
    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ordered.chunked(LEGEND_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { category ->
                    val isPicked = highlighted == category
                    val faded = highlighted != null && !isPicked
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPicked) colors[category.ordinal].copy(alpha = 0.16f) else Color.Transparent)
                            .then(
                                if (onCategoryClick == null) {
                                    Modifier
                                } else {
                                    Modifier.selectable(selected = isPicked, role = Role.Button) { onCategoryClick(category) }
                                },
                            )
                            .alpha(if (faded) 0.45f else 1f)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(colors[category.ordinal]))
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(stringResource(category.label), style = MaterialTheme.typography.labelLarge, color = colors[category.ordinal], maxLines = 1)
                            Text(formatDuration(totals[category] ?: 0L), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                repeat(LEGEND_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val LEGEND_COLUMNS = 3

/** "All apps" or "By category", above an app list. */
fun LazyListScope.appListToggle(byCategory: Boolean, onChange: (Boolean) -> Unit) {
    item(key = "app-list-toggle") {
        val options = listOf(false to R.string.apps_all, true to R.string.apps_by_category)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = byCategory == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
    }
}

/**
 * [apps] as one list, or under a heading per category ("Social, 1 h 13 min") with the most
 * used category first; [appRow] draws each app.
 */
fun LazyListScope.appListItems(
    apps: List<AppDay>,
    byCategory: Boolean,
    categoryOf: (String) -> AppCategory,
    /** Categories whose apps are shown; the others are folded into their heading. */
    expanded: Set<AppCategory> = AppCategory.entries.toSet(),
    onToggle: (AppCategory) -> Unit = {},
    appRow: @Composable (AppDay) -> Unit,
) {
    if (!byCategory) {
        items(apps, key = { it.packageName }) { appRow(it) }
        return
    }
    apps.groupBy { categoryOf(it.packageName) }
        .entries
        .sortedByDescending { (_, inCategory) -> inCategory.sumOf { it.foregroundMillis } }
        .forEach { (category, inCategory) ->
            val open = category in expanded
            item(key = "category-${category.name}") {
                CategoryHeading(category, inCategory.sumOf { it.foregroundMillis }, inCategory.size, open) { onToggle(category) }
            }
            if (open) items(inCategory, key = { it.packageName }) { appRow(it) }
        }
}

/** "Social, 3 apps, 1 h 13 min", tapped to show or hide its apps. */
@Composable
private fun CategoryHeading(category: AppCategory, millis: Long, appCount: Int, open: Boolean, onClick: () -> Unit) {
    val color = categoryColors()[category.ordinal]
    ListItem(
        headlineContent = { Text(stringResource(category.label), style = MaterialTheme.typography.titleSmall, color = color) },
        supportingContent = { Text(pluralStringResource(R.plurals.category_app_count, appCount, appCount)) },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(category), contentDescription = null, tint = color)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(millis), style = MaterialTheme.typography.titleSmall)
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(onClickLabel = stringResource(if (open) R.string.category_hide_apps else R.string.category_show_apps), onClick = onClick)
            .semantics(mergeDescendants = true) {
                heading()
            },
    )
}

/**
 * "Longest session 48 min (Instagram)", "After Bedtime use 21 min" and "During Focus 5 min",
 * for one day or a period (the longest of all; Bedtime and Focus as daily averages of the days
 * that had them). Each of those two rows only shows when asked for, or when a day has it.
 */
@Composable
fun SessionStats(
    days: Collection<DayExtras>,
    modifier: Modifier = Modifier,
    /** One day: plain values. Otherwise Bedtime and Focus show as daily averages. */
    singleDay: Boolean = days.size == 1,
    showBedtime: Boolean = days.any { it.afterBedtimeMillis != null },
    showFocus: Boolean = days.any { it.duringFocusMillis != null },
    /** The Bedtime row's title: the day view names the night ("Last night's…", "Previous night's…"). */
    bedtimeTitle: Int = R.string.after_bedtime,
) {
    val appInfo = (LocalContext.current.applicationContext as WellmuteApp).container.appInfo
    val longest = days.filter { it.longestSessionMillis > 0 }.maxByOrNull { it.longestSessionMillis }
    // A daily average over the days that had Bedtime hours, or Focus on.
    @Composable
    fun average(values: List<Long>): String {
        if (values.isEmpty()) return formatDuration(0)
        val average = values.sum() / values.size
        return if (singleDay) formatDuration(average) else stringResource(R.string.after_bedtime_average, formatDuration(average))
    }
    Column(modifier.fillMaxWidth()) {
        // The first time the phone was used each morning; over a period, the average time of day.
        val pickups = days.mapNotNull { it.firstPickupMillis }
        StatRow(
            stringResource(R.string.first_pickup),
            when {
                pickups.isEmpty() -> "–"
                singleDay -> timeOfDay(pickups.average().toLong())
                else -> stringResource(R.string.first_pickup_average, averageTimeOfDay(pickups))
            },
        )
        StatRow(
            stringResource(R.string.longest_session),
            longest?.let { day ->
                formatDuration(day.longestSessionMillis) + day.longestSessionPackage?.let { " (${appInfo.labelFor(it)})" }.orEmpty()
            } ?: "–",
        )
        // The day view calls it last night's; everywhere else it's "After Bedtime use".
        if (showBedtime) {
            StatRow(
                stringResource(bedtimeTitle),
                average(days.mapNotNull { it.afterBedtimeMillis }),
            )
        }
        // Tapping it opens Focus insights, when the screen offers them.
        if (showFocus) StatRow(stringResource(R.string.during_focus), average(days.mapNotNull { it.duringFocusMillis }), LocalOpenFocusInsights.current)
    }
}

@Composable
private fun StatRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClickLabel = stringResource(R.string.focus_insights_title), onClick = onClick))
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
    }
}

/** "07:42", in the phone's own time format. */
private fun timeOfDay(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** The average time of day of several moments on different days, like "07:58". */
private fun averageTimeOfDay(epochMillis: List<Long>): String {
    val zone = ZoneId.systemDefault()
    val averageSecond = epochMillis.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toSecondOfDay() }.average().toLong()
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.ofSecondOfDay(averageSecond))
}

/** Opens Focus insights from the Dashboard's "During Focus" row; null where there's nowhere to go. */
val LocalOpenFocusInsights = staticCompositionLocalOf<(() -> Unit)?> { null }
