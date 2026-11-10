package com.wellmute.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wellmute.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "2 h 5 min", "2 h", "12 min" or "< 1 min". */
@Composable
fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        millis in 1 until 60_000 -> stringResource(R.string.duration_less_than_minute)
        hours > 0 && minutes == 0L -> stringResource(R.string.duration_hours, hours)
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        else -> stringResource(R.string.duration_minutes, minutes)
    }
}

/** "Today", "Yesterday", or the date in the device's style. */
@Composable
fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

/** First letter of the weekday ("M", "T"…), for chart labels. */
fun weekdayInitial(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("EEEEE"))

/** "2h 5m", "2h", "12m" or "< 1 min": for tight spots like the middle of the screen time ring. */
@Composable
fun formatDurationCompact(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        millis in 1 until 60_000 -> stringResource(R.string.duration_less_than_minute)
        hours > 0 && minutes == 0L -> stringResource(R.string.duration_compact_hours, hours)
        hours > 0 -> stringResource(R.string.duration_compact_hours_minutes, hours, minutes)
        else -> stringResource(R.string.duration_compact_minutes, minutes)
    }
}
