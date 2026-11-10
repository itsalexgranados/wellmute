package com.wellmute.data

import com.wellmute.apps.AppCategories
import com.wellmute.apps.AppCategory
import com.wellmute.focus.FocusDayStats
import com.wellmute.usage.HourlyUsage
import com.wellmute.usage.Session
import com.wellmute.usage.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One app's numbers for a day. */
data class AppDay(
    val packageName: String,
    val foregroundMillis: Long,
    val opens: Int,
    val notifications: Int,
)

data class DaySummary(
    val day: LocalDate,
    /** Sorted by foreground time, then notifications. */
    val apps: List<AppDay>,
    val unlocks: Int,
    val extras: DayExtras = DayExtras(),
) {
    val totalMillis: Long get() = apps.sumOf { it.foregroundMillis }
    val totalNotifications: Int get() = apps.sumOf { it.notifications }
}

/** Per-day numbers beyond the per-app totals; empty for days saved before version 6. */
data class DayExtras(
    /** The longest stretch of one app in front, and which app; 0 when unknown. */
    val longestSessionMillis: Long = 0,
    val longestSessionPackage: String? = null,
    /** Screen time during Bedtime's scheduled hours; null when no Bedtime schedule covered the day. */
    val afterBedtimeMillis: Long? = null,
    /** Screen time while Focus mode was on; null when it wasn't on that day. */
    val duringFocusMillis: Long? = null,
    /** When the phone was first used that morning (epoch millis); null when unknown. */
    val firstPickupMillis: Long? = null,
)

/** One of the 7 days in the day view's chart, split by app category. */
data class WeekDay(val day: LocalDate, val byCategory: Map<AppCategory, Long>) {
    val totalMillis: Long get() = byCategory.values.sum()
}

data class PeriodSummary(
    val period: Period,
    /** Totals for the whole period, sorted like [DaySummary.apps]. */
    val apps: List<AppDay>,
    val unlocks: Int,
    /** Days that count toward averages: within history and not in the future. */
    val countedDays: Int,
    /** One per [Period.buckets]: the day's total, or for years the month's daily average. */
    val bars: List<Long>,
    val previousAverageMillis: Long?,
    /** [bars] split by app category, computed the same way. */
    val categoryBars: List<Map<AppCategory, Long>> = emptyList(),
    /** Screen time over the whole period by app category. */
    val categoryTotals: Map<AppCategory, Long> = emptyMap(),
    /** Each day's [DayExtras], for the period or a tapped bar. */
    val extras: Map<LocalDate, DayExtras> = emptyMap(),
) {
    val totalMillis: Long get() = apps.sumOf { it.foregroundMillis }
    val totalNotifications: Int get() = apps.sumOf { it.notifications }
    val averageMillis: Long get() = if (countedDays == 0) 0 else totalMillis / countedDays
}

enum class Metric { SCREEN_TIME, NOTIFICATIONS, OPENS }

/** One app's metric over a week, month or year (see [HistoryRepository.observeAppPeriod]). */
data class AppPeriod(
    /** One per [Period.buckets]: the day's value, or for years the month's daily average. */
    val bars: List<Long>,
    val total: Long,
    /** Days that count toward the daily average: within history and not in the future. */
    val countedDays: Int,
    /** The period before's daily average, to compare with; null when there's no fair one. */
    val previousAverage: Long? = null,
) {
    val dailyAverage: Long get() = if (countedDays == 0) 0 else total / countedDays
}

/**
 * Usage history kept in our own database, because Android only keeps usage events for a few
 * days. Days are recalculated from the events while they exist and stored per app and hour.
 */
class HistoryRepository(
    private val dao: HistoryDao,
    private val usage: UsageRepository,
    private val categories: AppCategories,
    /** Bedtime's scheduled time within a day, for "after Bedtime use"; empty when there's no schedule. */
    private val bedtimeWindows: (LocalDate) -> List<LongRange>,
) {

    /** Recalculates the last [days] days, today included. */
    suspend fun syncRecentDays(days: Int = SYNCED_DAYS) {
        val today = LocalDate.now()
        for (offset in days - 1 downTo 0) syncDay(today.minusDays(offset.toLong()))
    }

    suspend fun syncDay(day: LocalDate) = withContext(Dispatchers.IO) {
        if (!usage.hasUsageAccess()) return@withContext
        val (hours, unlocks, sessions) = usage.hourlyUsageFor(day)
        val rows = hours.flatMap { hour ->
            hour.apps.map { AppUsageRow(day.toEpochDay(), hour.hour, it.packageName, it.foregroundMillis, it.opens) }
        }
        // Once Android has dropped a day's events, keep what we saved instead of wiping it.
        if (rows.isEmpty() && dao.totalMillis(day.toEpochDay()) > 0) return@withContext
        val longest = longestSession(sessions)
        val windows = bedtimeWindows(day)
        val afterBedtime = if (windows.isEmpty()) null else sessions.sumOf { s -> windows.sumOf { w -> overlap(s.start, s.end, w) } }
        val firstPickup = firstPickup(day, sessions)
        val focus = focusWindows(day)
        val duringFocus = focus.takeIf { it.isNotEmpty() }?.let { sessions.sumOf { s -> it.sumOf { w -> overlap(s.start, s.end, w) } } }
        // Focus insights: apps used during Focus and the time the phone wasn't used.
        val focusStats = FocusDayStats.of(sessions, focus)
        dao.replaceDay(
            day.toEpochDay(),
            rows,
            DayTotalsRow(
                day.toEpochDay(), unlocks, longest?.millis ?: 0, longest?.packageName, afterBedtime, duringFocus, firstPickup,
                focusMillis = focusStats?.focusMillis,
                focusIdleMillis = focusStats?.idleMillis,
                focusLongestIdleMillis = focusStats?.longestIdleMillis,
            ),
        )
        dao.replaceFocusAppDay(
            day.toEpochDay(),
            focusStats?.apps.orEmpty().map { FocusAppDayRow(day.toEpochDay(), it.packageName, it.millis, it.opens) },
        )
    }

    /**
     * The longest stretch of one app. Stretches of the same app a moment apart (moving between
     * its screens) are joined, as they are for counting opens.
     */
    private fun longestSession(sessions: List<Session>): Session? {
        val joined = mutableListOf<Session>()
        for (session in sessions.sortedBy { it.start }) {
            val last = joined.lastOrNull()
            if (last != null && last.packageName == session.packageName && session.start - last.end <= SAME_VISIT_GAP_MILLIS) {
                joined[joined.lastIndex] = last.copy(end = maxOf(last.end, session.end))
            } else {
                joined += session
            }
        }
        return joined.maxByOrNull { it.millis }
    }

    /** When Focus mode was on within [day], from the stretches [com.wellmute.focus.FocusHistory] saved. */
    private suspend fun focusWindows(day: LocalDate): List<LongRange> {
        val zone = ZoneId.systemDefault()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        return dao.focusPeriodsBetween(dayStart, dayEnd).mapNotNull { period ->
            val start = maxOf(period.start, dayStart)
            val end = minOf(period.end ?: now, dayEnd)
            (start until end).takeIf { end > start }
        }
    }

    /**
     * The first time the phone was used on [day], counting from 03:00, so a look in the middle of
     * the night isn't "the first of the day". The same for everyone, with or without Bedtime.
     */
    private fun firstPickup(day: LocalDate, sessions: List<Session>): Long? {
        val from = day.atTime(FIRST_PICKUP_FROM).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A pickup is an app being opened, so one still running from before doesn't count.
        return sessions.filter { it.start >= from }.minOfOrNull { it.start }
    }

    private fun overlap(start: Long, end: Long, window: LongRange): Long =
        (minOf(end, window.last + 1) - maxOf(start, window.first)).coerceAtLeast(0)

    /** Each day's screen time split by app category, over [from]..[to]; follows category changes. */
    fun observeCategoryDays(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Map<AppCategory, Long>>> =
        combine(dao.observeAppDailyAll(from.toEpochDay(), to.toEpochDay()), categories.chosen) { rows, chosen ->
            rows.groupBy { LocalDate.ofEpochDay(it.day) }.mapValues { (_, apps) ->
                apps.groupBy({ categories.categoryOf(it.packageName, chosen) }, { it.value }).mapValues { it.value.sum() }
            }
        }

    private fun observeExtras(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayExtras>> =
        dao.observeDayTotalRows(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.associate { LocalDate.ofEpochDay(it.day) to DayExtras(it.longestSessionMillis, it.longestSessionPackage, it.afterBedtimeMillis, it.duringFocusMillis, it.firstPickupMillis) }
        }

    suspend fun recordNotification(postedAt: Long, packageName: String) = withContext(Dispatchers.IO) {
        val (day, hour) = dayAndHour(postedAt)
        dao.incrementNotifications(day.toEpochDay(), hour, packageName)
    }

    fun observeDay(day: LocalDate): Flow<DaySummary> =
        combine(observeRange(day, day), observeExtras(day, day)) { (apps, unlocks), extras ->
            DaySummary(day, apps, unlocks, extras[day] ?: DayExtras())
        }

    /** A week, month or year: per-app totals, the chart's bars and the previous period's daily average. */
    fun observePeriod(period: Period, today: LocalDate = LocalDate.now()): Flow<PeriodSummary> {
        val previous = period.previous()
        return combine(
            observeRange(period.start, period.end),
            dao.observeDailyTotals(previous.start.toEpochDay(), period.end.toEpochDay()),
            observeFirstDay(),
            observeCategoryDays(period.start, period.end),
            observeExtras(period.start, period.end),
        ) { (apps, unlocks), daily, firstDay, categoryDays, extras ->
            val byDay = daily.associate { LocalDate.ofEpochDay(it.day) to it.value }
            fun total(from: LocalDate, to: LocalDate) = byDay.filterKeys { it in from..to }.values.sum()
            fun average(from: LocalDate, to: LocalDate): Long? {
                val days = countedDays(from, to, firstDay, today)
                return if (days == 0) null else total(from, to) / days
            }
            val bars = period.buckets().map { bucket ->
                // A year's bars are months of different lengths, so they show the daily average.
                if (period.type == PeriodType.YEAR) average(bucket.start, bucket.end) ?: 0L else total(bucket.start, bucket.end)
            }
            val categoryBars = period.buckets().map { bucket ->
                val inBucket = categoryDays.filterKeys { it in bucket.start..bucket.end }.values
                val sums = AppCategory.entries.associateWith { category -> inBucket.sumOf { it[category] ?: 0L } }
                val days = if (period.type == PeriodType.YEAR) countedDays(bucket.start, bucket.end, firstDay, today) else 1
                if (days == 0) emptyMap() else sums.mapValues { it.value / days }.filterValues { it > 0 }
            }
            PeriodSummary(
                period = period,
                apps = apps,
                unlocks = unlocks,
                countedDays = countedDays(period.start, period.end, firstDay, today),
                bars = bars,
                previousAverageMillis = comparableAverage(previous, byDay, firstDay, today),
                categoryBars = categoryBars,
                categoryTotals = AppCategory.entries.associateWith { c -> categoryDays.values.sumOf { it[c] ?: 0L } }.filterValues { it > 0 },
                extras = extras,
            )
        }
    }

    /** Every app's totals and the unlocks over [from]..[to]. */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Pair<List<AppDay>, Int>> = combine(
        dao.observeAppTotals(from.toEpochDay(), to.toEpochDay()),
        dao.observeNotificationCounts(from.toEpochDay(), to.toEpochDay()),
        dao.observeUnlocks(from.toEpochDay(), to.toEpochDay()),
    ) { usage, notifications, unlocks ->
        val notificationsByApp = notifications.associate { it.packageName to it.count }
        val usageByApp = usage.associateBy { it.packageName }
        val apps = (usageByApp.keys + notificationsByApp.keys).map { pkg ->
            AppDay(pkg, usageByApp[pkg]?.foregroundMillis ?: 0, usageByApp[pkg]?.opens ?: 0, notificationsByApp[pkg] ?: 0)
        }.sortedWith(compareByDescending<AppDay> { it.foregroundMillis }.thenByDescending { it.notifications })
        apps to unlocks
    }

    /** Screen time for each of the 7 days ending on [lastDay], oldest first, split by app category. */
    fun observeWeek(lastDay: LocalDate): Flow<List<WeekDay>> {
        val first = lastDay.minusDays(6)
        return observeCategoryDays(first, lastDay).map { byDay ->
            (0L until 7L).map { offset -> first.plusDays(offset).let { day -> WeekDay(day, byDay[day].orEmpty()) } }
        }
    }

    /**
     * [period]'s daily average, for comparing with the one after it, or null when there's too little
     * of it to compare fairly. The first day of history is left out: Wellmute was installed partway
     * through it, and its few minutes made changes look huge ("3915% more").
     */
    private fun comparableAverage(period: Period, byDay: Map<LocalDate, Long>, firstDay: LocalDate?, today: LocalDate): Long? =
        comparableDays(period, byDay, firstDay, today)?.let { days -> days.sumOf { byDay.getValue(it) } / days.size }

    /**
     * The days of [period] fit for comparing, from the phone's daily screen time in [byDay], or
     * null when there are too few of them.
     */
    private fun comparableDays(period: Period, byDay: Map<LocalDate, Long>, firstDay: LocalDate?, today: LocalDate): List<LocalDate>? {
        firstDay ?: return null
        val start = maxOf(period.start, firstDay.plusDays(1))
        val end = minOf(period.end, today)
        // Days with almost no screen time are gaps in the data (Android had only scraps of them
        // when Wellmute started), not real days: counting them gave "40582% more".
        val tracked = byDay.filter { (day, millis) -> day in start..end && millis >= TRACKED_DAY_MILLIS }.keys.toList()
        return tracked.takeIf { it.size >= minDaysToCompare(period.type) }
    }

    /** Fewest days with data a previous period needs to be compared with: enough to be fair. */
    private fun minDaysToCompare(type: PeriodType): Int = when (type) {
        PeriodType.WEEK -> 3
        PeriodType.MONTH -> 7
        PeriodType.YEAR -> 30
    }

    /**
     * Screen time on [day], for comparing a day with the same weekday a week before. With
     * [untilHour], only the hours before it: today isn't over, so it's compared up to the same time.
     */
    fun observeDayTotal(day: LocalDate, untilHour: Int? = null): Flow<Long> =
        if (untilHour != null) {
            dao.observeTotalBefore(day.toEpochDay(), untilHour)
        } else {
            dao.observeDailyTotals(day.toEpochDay(), day.toEpochDay()).map { rows -> rows.sumOf { it.value } }
        }

    fun observeFirstDay(): Flow<LocalDate?> = dao.observeFirstDay().map { it?.let(LocalDate::ofEpochDay) }

    /** One app's [metric] for each hour of [day] (index = hour since midnight; 25 on a long DST day). */
    fun observeAppHours(packageName: String, day: LocalDate, metric: Metric): Flow<List<Long>> {
        val epochDay = day.toEpochDay()
        val hourly = when (metric) {
            Metric.SCREEN_TIME -> dao.observeAppHourlyMillis(packageName, epochDay)
            Metric.NOTIFICATIONS -> dao.observeAppHourlyNotifications(packageName, epochDay)
            Metric.OPENS -> dao.observeAppHourlyOpens(packageName, epochDay)
        }
        return hourly.map { hours ->
            val byHour = hours.associate { it.hour to it.value }
            val hourCount = maxOf(24, (byHour.keys.maxOrNull() ?: 0) + 1)
            List(hourCount) { byHour[it] ?: 0L }
        }
    }

    /**
     * One app's [metric] over a week, month or year: one bar per day (per month for a year, as a
     * daily average, since months differ in length), the total, and the days that count toward
     * averages, like the dashboard's period views.
     */
    fun observeAppPeriod(packageName: String, period: Period, metric: Metric, today: LocalDate = LocalDate.now()): Flow<AppPeriod> {
        val previous = period.previous()
        val from = previous.start.toEpochDay()
        val to = period.end.toEpochDay()
        val daily = when (metric) {
            Metric.SCREEN_TIME -> dao.observeAppDailyMillis(packageName, from, to)
            Metric.NOTIFICATIONS -> dao.observeAppDailyNotifications(packageName, from, to)
            Metric.OPENS -> dao.observeAppDailyOpens(packageName, from, to)
        }
        return combine(daily, dao.observeDailyTotals(from, to), observeFirstDay()) { rows, deviceDaily, firstDay ->
            val byDay = rows.associate { LocalDate.ofEpochDay(it.day) to it.value }
            fun total(start: LocalDate, end: LocalDate) = byDay.filterKeys { it in start..end }.values.sum()
            val bars = period.buckets().map { bucket ->
                if (period.type == PeriodType.YEAR) {
                    val days = countedDays(bucket.start, bucket.end, firstDay, today)
                    if (days == 0) 0L else total(bucket.start, bucket.end) / days
                } else {
                    total(bucket.start, bucket.end)
                }
            }
            // The previous period counts the same days the dashboard's comparison does: ones the phone
            // was tracked (not the first, partial day, nor data gaps), including days without this app.
            val tracked = comparableDays(previous, deviceDaily.associate { LocalDate.ofEpochDay(it.day) to it.value }, firstDay, today)
            val previousAverage = tracked?.let { days -> days.sumOf { byDay[it] ?: 0L } / days.size }
            AppPeriod(bars, total(period.start, period.end), countedDays(period.start, period.end, firstDay, today), previousAverage)
        }
    }

    /** The day and the hour since local midnight, matching how [HourlyUsage] numbers hours. */
    private fun dayAndHour(timestamp: Long): Pair<LocalDate, Int> {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return day to ((timestamp - dayStart) / HourlyUsage.HOUR_MILLIS).toInt()
    }

    private companion object {
        /** Android keeps usage events for roughly a week; resyncing that much catches late changes. */
        const val SYNCED_DAYS = 7

        /** Like UsageCalculator's: stretches of one app this close together are one visit. */
        const val SAME_VISIT_GAP_MILLIS = 2_000L

        /** The first pickup of a day counts from this time. */
        val FIRST_PICKUP_FROM: LocalTime = LocalTime.of(3, 0)
    }
}
