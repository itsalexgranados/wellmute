package com.wellmute.focus

import com.wellmute.schedule.WeeklySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class FocusSettingsTest {

    private val zone = ZoneId.of("Europe/Madrid")

    /** 2026-11-02 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant()

    private val workdays = WeeklySchedule(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)

    @Test
    fun `off by default`() {
        assertFalse(FocusSettings().isActive(at(2, 10), zone))
    }

    @Test
    fun `schedule is on inside the window on scheduled days only`() {
        val settings = FocusSettings(schedule = workdays)
        assertTrue(settings.isActive(at(2, 9), zone))
        assertTrue(settings.isActive(at(2, 16, 59), zone))
        assertFalse(settings.isActive(at(2, 17), zone))
        assertFalse(settings.isActive(at(2, 8, 59), zone))
        assertFalse(settings.isActive(at(7, 10), zone)) // Saturday
    }

    @Test
    fun `overnight window belongs to the day it starts`() {
        val nights = FocusSettings(schedule = WeeklySchedule(enabled = true, days = setOf(DayOfWeek.FRIDAY), startMinute = 22 * 60, endMinute = 7 * 60))
        assertTrue(nights.isActive(at(6, 23), zone)) // Friday night
        assertTrue(nights.isActive(at(7, 6), zone)) // Saturday morning, same window
        assertFalse(nights.isActive(at(7, 23), zone)) // Saturday night isn't scheduled
    }

    @Test
    fun `turning off during the window lasts until the window ends`() {
        val off = FocusSettings(schedule = workdays).turnedOff(at(2, 10), zone)
        assertFalse(off.isActive(at(2, 16), zone))
        assertTrue(off.isActive(at(3, 10), zone)) // next day's window starts as usual
    }

    @Test
    fun `turned on by hand stays on outside the schedule`() {
        val on = FocusSettings(schedule = workdays).turnedOn()
        assertTrue(on.isActive(at(2, 20), zone))
        assertTrue(on.isActive(at(7, 10), zone))
    }

    @Test
    fun `a break pauses focus and it comes back by itself`() {
        val settings = FocusSettings().turnedOn().onBreak(at(2, 10), minutes = 15)
        assertFalse(settings.isActive(at(2, 10, 14), zone))
        assertTrue(settings.isActive(at(2, 10, 15), zone))
        assertTrue(settings.isOnBreak(at(2, 10, 5)))
    }

    @Test
    fun `next change is the nearest of break end and schedule boundary`() {
        val settings = FocusSettings(schedule = workdays).onBreak(at(2, 16, 50), minutes = 30)
        assertEquals(at(2, 17), settings.nextChange(at(2, 16, 50), zone))
        assertEquals(at(3, 9), FocusSettings(schedule = workdays).nextChange(at(2, 18), zone))
        assertEquals(at(9, 9), FocusSettings(schedule = workdays).nextChange(at(6, 17, 30), zone)) // Friday evening → Monday
    }
}
