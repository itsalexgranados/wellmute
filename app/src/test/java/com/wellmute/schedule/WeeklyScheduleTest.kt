package com.wellmute.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class WeeklyScheduleTest {

    private val zone = ZoneOffset.UTC
    private val hour = 3_600_000L

    @Test
    fun `an overnight window covers the end of last night and the start of tonight`() {
        val schedule = WeeklySchedule(enabled = true, days = DayOfWeek.entries.toSet(), startMinute = 22 * 60, endMinute = 7 * 60)
        val day = LocalDate.of(2026, 11, 6)
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(listOf(midnight until midnight + 7 * hour, midnight + 22 * hour until midnight + 24 * hour), schedule.windowsWithin(day, zone))
    }

    @Test
    fun `no windows when the schedule is off or the day isn't in it`() {
        val day = LocalDate.of(2026, 11, 6) // A Friday.
        assertEquals(emptyList<LongRange>(), WeeklySchedule(enabled = false).windowsWithin(day, zone))
        val weekends = WeeklySchedule(enabled = true, days = setOf(DayOfWeek.SATURDAY), startMinute = 9 * 60, endMinute = 12 * 60)
        assertEquals(emptyList<LongRange>(), weekends.windowsWithin(day, zone))
    }
}
