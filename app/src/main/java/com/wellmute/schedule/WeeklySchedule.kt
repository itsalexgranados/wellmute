package com.wellmute.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A repeating weekly time window, used by Focus mode and Bedtime mode. A window starts on each
 * of [days] at [startMinute] and ends at [endMinute], the next day if the end is earlier than the
 * start (e.g. 22:00–07:00).
 */
data class WeeklySchedule(
    val enabled: Boolean = false,
    val days: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet(),
    /** Minutes since midnight. */
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
) {
    /** The scheduled window containing [now], if any. */
    fun windowAt(now: ZonedDateTime): ClosedRange<ZonedDateTime>? {
        if (!enabled) return null
        return windowsAround(now).firstOrNull { now >= it.start && now < it.endInclusive }
    }

    /** The next time a window starts or ends after [now]. */
    fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? {
        if (!enabled) return null
        return windowsAround(now, daysAhead = 8).flatMap { listOf(it.start, it.endInclusive) }.filter { it > now }.minOrNull()
    }

    /** The scheduled time within [day], as epoch-millis ranges: last night's tail and tonight's start. */
    fun windowsWithin(day: LocalDate, zone: ZoneId): List<LongRange> {
        if (!enabled) return emptyList()
        val dayStart = day.atStartOfDay(zone)
        val dayEnd = day.plusDays(1).atStartOfDay(zone)
        return windowsAround(dayStart.plusHours(12)).mapNotNull { window ->
            val start = maxOf(window.start, dayStart).toInstant().toEpochMilli()
            val end = minOf(window.endInclusive, dayEnd).toInstant().toEpochMilli()
            (start until end).takeIf { end > start }
        }
    }

    private fun windowsAround(now: ZonedDateTime, daysAhead: Long = 0): List<ClosedRange<ZonedDateTime>> =
        (-1L..daysAhead).mapNotNull { offset ->
            val day = now.toLocalDate().plusDays(offset)
            if (day.dayOfWeek !in days) return@mapNotNull null
            val start = day.atStartOfDay(now.zone).plusMinutes(startMinute.toLong())
            val endDay = if (endMinute > startMinute) day else day.plusDays(1)
            start..endDay.atStartOfDay(now.zone).plusMinutes(endMinute.toLong())
        }
}
