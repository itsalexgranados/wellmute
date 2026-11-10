package com.wellmute.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wellmute.R
import com.wellmute.apps.AppInfoRepository
import com.wellmute.data.DaySummary
import com.wellmute.ui.common.DonutChart
import com.wellmute.ui.common.DonutSlice
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.common.formatDurationCompact
import com.wellmute.ui.common.otherSliceColor
import com.wellmute.ui.common.sliceColors

/** The day's screen time as a donut of the top apps, with unlocks and notifications below. */
@Composable
fun DayOverview(
    summary: DaySummary,
    appInfo: AppInfoRepository,
    hasNotificationAccess: Boolean,
    @StringRes centerLabel: Int,
    modifier: Modifier = Modifier,
) {
    val colors = sliceColors()
    val topApps = summary.apps.filter { it.foregroundMillis > 0 }.take(colors.size)
    val otherMillis = summary.totalMillis - topApps.sumOf { it.foregroundMillis }
    val slices = topApps.mapIndexed { index, app -> DonutSlice(app.foregroundMillis.toFloat(), colors[index]) } +
        DonutSlice(otherMillis.toFloat(), otherSliceColor())
    val total = formatDuration(summary.totalMillis)
    val description = stringResource(
        R.string.dashboard_donut_description,
        total,
        topApps.joinToString { appInfo.labelFor(it.packageName) },
    )

    // Tapping a slice shows that app in the middle; it resets when the day or its apps change.
    var selected by remember(summary.day, topApps.map { it.packageName }) { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DonutChart(
            slices = slices,
            description = description,
            selectedIndex = selected,
            onSliceClick = { index -> selected = if (index == selected) null else index },
        ) {
            val index = selected
            Column(Modifier.width(140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (index == null) {
                    FitText(formatDurationCompact(summary.totalMillis), MaterialTheme.typography.headlineMedium)
                    Text(stringResource(centerLabel), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val millis = topApps.getOrNull(index)?.foregroundMillis ?: otherMillis
                    val name = topApps.getOrNull(index)?.let { appInfo.labelFor(it.packageName) }
                        ?: stringResource(R.string.dashboard_other_apps)
                    val percent = if (summary.totalMillis > 0) (millis * 100 / summary.totalMillis).toInt() else 0
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FitText(formatDurationCompact(millis), MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.dashboard_share_of_day, percent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(summary.unlocks.toString(), R.string.home_unlocks)
            Stat(if (hasNotificationAccess) summary.totalNotifications.toString() else "–", R.string.home_notifications)
        }
    }
}

@Composable
internal fun Stat(value: String, @StringRes label: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One line that shrinks to fit instead of wrapping: in the middle of the ring a wrapped time
 * ("3 h 28 / min") broke the layout.
 */
@Composable
private fun FitText(text: String, style: TextStyle) {
    BasicText(
        text,
        style = style.merge(color = LocalContentColor.current, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize),
    )
}
