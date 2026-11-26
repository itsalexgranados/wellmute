package com.wellmute.focus

import com.wellmute.schedule.WeeklySchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime

class FocusScheduleChangeTest {

    private val zone = ZoneOffset.UTC
    private val everyDay = DayOfWeek.entries.toSet()

    @Test
    fun `a new schedule starting a minute later turns Focus on, even after turning it off by hand earlier`() {
        // 16:30, inside a 09:00–17:00 schedule: turned off by hand, so off until 17:00.
        val at1630 = ZonedDateTime.of(2026, 11, 26, 16, 30, 0, 0, zone).toInstant()
        val turnedOff = FocusSettings(schedule = WeeklySchedule(enabled = true, days = everyDay, startMinute = 9 * 60, endMinute = 17 * 60))
            .turnedOff(at1630, zone)
        // 16:54: the schedule is changed to start at 16:55.
        val rescheduled = turnedOff.rescheduled(WeeklySchedule(enabled = true, days = everyDay, startMinute = 16 * 60 + 55, endMinute = 18 * 60))
        val at1654 = ZonedDateTime.of(2026, 11, 26, 16, 54, 30, 0, zone).toInstant()
        val at1655 = ZonedDateTime.of(2026, 11, 26, 16, 55, 30, 0, zone).toInstant()
        assertFalse(rescheduled.isActive(at1654, zone))
        assertTrue(rescheduled.isActive(at1655, zone))
    }

    @Test
    fun `turned off by hand stays off while the schedule isn't touched`() {
        val schedule = WeeklySchedule(enabled = true, days = everyDay, startMinute = 9 * 60, endMinute = 17 * 60)
        val at1630 = ZonedDateTime.of(2026, 11, 26, 16, 30, 0, 0, zone).toInstant()
        val turnedOff = FocusSettings(schedule = schedule).turnedOff(at1630, zone)
        // Saving the same schedule again (no real change) keeps it off until 17:00.
        val same = turnedOff.rescheduled(schedule)
        assertFalse(same.isActive(ZonedDateTime.of(2026, 11, 26, 16, 45, 0, 0, zone).toInstant(), zone))
    }
}
