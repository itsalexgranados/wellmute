package com.wellmute.usage

import com.wellmute.usage.HourlyUsage.HOUR_MILLIS
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_PAUSED
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_RESUMED
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test

class HourlyUsageTest {

    private val minute = 60_000L
    private val dayEnd = 24 * HOUR_MILLIS

    private fun event(hour: Int, minutes: Int, type: UsageEvent.Type, pkg: String = "a") =
        UsageEvent(hour * HOUR_MILLIS + minutes * minute, type, pkg, "$pkg.Main")

    @Test
    fun `session crossing an hour is split and opened once`() {
        val events = listOf(event(9, 50, ACTIVITY_RESUMED), event(10, 15, ACTIVITY_PAUSED), event(10, 15, ACTIVITY_STOPPED))
        val hours = HourlyUsage.calculate(events, dayStart = 0, dayEnd = dayEnd, end = dayEnd)

        assertEquals(listOf(9, 10), hours.map { it.hour })
        assertEquals(10 * minute, hours[0].apps.single().foregroundMillis)
        assertEquals(15 * minute, hours[1].apps.single().foregroundMillis)
        assertEquals(listOf(1, 0), hours.map { it.apps.single().opens })
    }

    @Test
    fun `hours add up to the whole day`() {
        val events = listOf(
            event(1, 10, ACTIVITY_RESUMED), event(3, 20, ACTIVITY_PAUSED), event(3, 20, ACTIVITY_STOPPED),
            event(3, 20, ACTIVITY_RESUMED, "b"), event(3, 45, ACTIVITY_PAUSED, "b"), event(3, 45, ACTIVITY_STOPPED, "b"),
            event(22, 0, ACTIVITY_RESUMED), event(23, 59, ACTIVITY_PAUSED), event(23, 59, ACTIVITY_STOPPED),
        )
        val day = UsageCalculator.calculate(events, 0, dayEnd, mode = CountingMode.VISIBLE)
        val hours = HourlyUsage.calculate(events, dayStart = 0, dayEnd = dayEnd, end = dayEnd)

        assertEquals(day.totalMillis, hours.sumOf { hour -> hour.apps.sumOf { it.foregroundMillis } })
        assertEquals(day.apps.sumOf { it.opens }, hours.sumOf { hour -> hour.apps.sumOf { it.opens } })
    }

    @Test
    fun `today stops at now`() {
        val events = listOf(event(8, 0, ACTIVITY_RESUMED))
        val hours = HourlyUsage.calculate(events, dayStart = 0, dayEnd = dayEnd, end = 8 * HOUR_MILLIS + 30 * minute)

        assertEquals(listOf(8), hours.map { it.hour })
        assertEquals(30 * minute, hours.single().apps.single().foregroundMillis)
    }

    @Test
    fun `clock change day with 25 hours has an hour 24`() {
        val events = listOf(event(24, 0, ACTIVITY_RESUMED), event(24, 30, ACTIVITY_PAUSED), event(24, 30, ACTIVITY_STOPPED))
        val hours = HourlyUsage.calculate(events, dayStart = 0, dayEnd = 25 * HOUR_MILLIS, end = 25 * HOUR_MILLIS)
        assertEquals(24, hours.single().hour)
    }
}
