package com.wellmute.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PeriodsTest {

    /** 2026-11-05 is a Thursday. */
    private val thursday = LocalDate.of(2026, 11, 5)

    @Test
    fun weekStartsOnTheLocaleFirstDay() {
        assertEquals(LocalDate.of(2026, 11, 2), Period.containing(PeriodType.WEEK, thursday, DayOfWeek.MONDAY).start)
        assertEquals(LocalDate.of(2026, 11, 1), Period.containing(PeriodType.WEEK, thursday, DayOfWeek.SUNDAY).start)
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(monday, Period.containing(PeriodType.WEEK, monday, DayOfWeek.MONDAY).start)
    }

    @Test
    fun weekHasSevenDailyBuckets() {
        val week = Period.containing(PeriodType.WEEK, thursday, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 8), week.end)
        assertEquals(7, week.buckets().size)
        assertTrue(thursday in week)
    }

    @Test
    fun monthBucketsFollowTheMonthLength() {
        assertEquals(30, Period.containing(PeriodType.MONTH, thursday, DayOfWeek.MONDAY).buckets().size)
        val february = Period.containing(PeriodType.MONTH, LocalDate.of(2028, 2, 10), DayOfWeek.MONDAY)
        assertEquals(29, february.buckets().size)
        assertEquals(LocalDate.of(2028, 2, 29), february.end)
    }

    @Test
    fun yearHasOneBucketPerMonth() {
        val year = Period.containing(PeriodType.YEAR, thursday, DayOfWeek.MONDAY)
        val buckets = year.buckets()
        assertEquals(12, buckets.size)
        assertEquals(LocalDate.of(2026, 2, 28), buckets[1].end)
        assertEquals(LocalDate.of(2026, 12, 31), year.end)
    }

    @Test
    fun previousAndNextMoveByWholePeriods() {
        val january = Period.containing(PeriodType.MONTH, LocalDate.of(2027, 1, 31), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 12, 1), january.previous().start)
        assertEquals(LocalDate.of(2027, 2, 28), january.next().end)
    }

    @Test
    fun countedDaysSkipsTheFutureAndBeforeHistory() {
        val week = Period.containing(PeriodType.WEEK, thursday, DayOfWeek.MONDAY)
        assertEquals(4, countedDays(week.start, week.end, LocalDate.of(2026, 1, 1), thursday))
        assertEquals(2, countedDays(week.start, week.end, LocalDate.of(2026, 11, 4), thursday))
        assertEquals(0, countedDays(week.start, week.end, null, thursday))
        assertEquals(0, countedDays(week.next().start, week.next().end, LocalDate.of(2026, 1, 1), thursday))
    }

    @Test
    fun percentChangeNeedsSomethingToCompare() {
        assertNull(percentChange(0, 100))
        assertEquals(-25, percentChange(400, 300))
        assertEquals(50, percentChange(200, 300))
    }
}
