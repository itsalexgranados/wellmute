package com.wellmute.ui.dashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.data.PeriodType
import com.wellmute.data.percentChange
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.abs

/**
 * "⬇ 19% from last week", next to an average or a total: how it compares with the period before
 * ([type]), or with the same weekday a week before (null [type], for [day]). Shows nothing when
 * there's nothing fair to compare with ([previous] is null or zero).
 */
@Composable
fun TrendBadge(previous: Long?, current: Long, type: PeriodType?, day: LocalDate, modifier: Modifier = Modifier) {
    val change = percentChange(previous ?: return, current) ?: return
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])
    val text = if (change == 0) {
        when (type) {
            PeriodType.WEEK -> stringResource(R.string.trend_same_week)
            PeriodType.MONTH -> stringResource(R.string.trend_same_month)
            PeriodType.YEAR -> stringResource(R.string.trend_same_year)
            null -> stringResource(R.string.trend_same_weekday, weekday)
        }
    } else {
        val percent = abs(change)
        when (type) {
            PeriodType.WEEK -> stringResource(R.string.trend_week, percent)
            PeriodType.MONTH -> stringResource(R.string.trend_month, percent)
            PeriodType.YEAR -> stringResource(R.string.trend_year, percent)
            null -> stringResource(R.string.trend_weekday, percent, weekday)
        }
    }
    // Screen readers hear "Down 19% from last week" rather than an unlabelled arrow.
    val spoken = when {
        change < 0 -> stringResource(R.string.trend_down, text)
        change > 0 -> stringResource(R.string.trend_up, text)
        else -> text
    }
    Row(
        modifier.widthIn(max = 200.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
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
            textAlign = TextAlign.Start,
            maxLines = 2,
        )
    }
}
