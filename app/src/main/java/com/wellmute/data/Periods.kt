package com.wellmute.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class PeriodType { WEEK, MONTH, YEAR }

/** A calendar week, month or year, from [start] to [end] inclusive. */
data class Period(val type: PeriodType, val start: LocalDate) {

    val end: LocalDate
        get() = when (type) {
            PeriodType.WEEK -> start.plusDays(6)
            PeriodType.MONTH -> start.plusMonths(1).minusDays(1)
            PeriodType.YEAR -> start.plusYears(1).minusDays(1)
        }

    fun previous(): Period = shift(-1)

    fun next(): Period = shift(1)

    operator fun contains(day: LocalDate): Boolean = day in start..end

    /**
     * The chart's bars: one per day for weeks and months, one per month for years.
     * Each bar covers [Bucket.start]..[Bucket.end].
     */
    fun buckets(): List<Bucket> = when (type) {
        PeriodType.WEEK, PeriodType.MONTH -> generateSequence(start) { it.plusDays(1) }
            .takeWhile { it <= end }
            .map { Bucket(it, it) }
            .toList()
        PeriodType.YEAR -> (0L until 12L).map { month ->
            val first = start.plusMonths(month)
            Bucket(first, first.plusMonths(1).minusDays(1))
        }
    }

    private fun shift(by: Long): Period = when (type) {
        PeriodType.WEEK -> copy(start = start.plusWeeks(by))
        PeriodType.MONTH -> copy(start = start.plusMonths(by))
        PeriodType.YEAR -> copy(start = start.plusYears(by))
    }

    companion object {
        /** The period of [type] that [day] falls in; weeks begin on [firstDayOfWeek]. */
        fun containing(type: PeriodType, day: LocalDate, firstDayOfWeek: DayOfWeek): Period = when (type) {
            PeriodType.WEEK -> Period(type, day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)))
            PeriodType.MONTH -> Period(type, day.withDayOfMonth(1))
            PeriodType.YEAR -> Period(type, day.withDayOfYear(1))
        }
    }
}

data class Bucket(val start: LocalDate, val end: LocalDate)

/**
 * Days of [from]..[to] that count toward a daily average: none before history began
 * ([firstDay]) and none after [today], so a half-finished week isn't averaged over 7 days.
 */
fun countedDays(from: LocalDate, to: LocalDate, firstDay: LocalDate?, today: LocalDate): Int {
    if (firstDay == null) return 0
    val start = maxOf(from, firstDay)
    val end = minOf(to, today)
    return if (end < start) 0 else (end.toEpochDay() - start.toEpochDay() + 1).toInt()
}

/** Percentage change from [before] to [now], or null when there's nothing to compare with. */
fun percentChange(before: Long, now: Long): Int? =
    if (before <= 0) null else ((now - before) * 100.0 / before).roundToInt()

/** A day with less screen time than this is treated as a gap in the data when comparing. */
const val TRACKED_DAY_MILLIS = 5 * 60_000L
