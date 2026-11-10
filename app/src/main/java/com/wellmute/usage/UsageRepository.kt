package com.wellmute.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Reads usage events from Android and turns them into daily summaries. */
class UsageRepository(private val context: Context) {

    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val packageManager = context.packageManager

    // Deprecated in API 37, but still how usage access is checked; revisit when we raise minSdk.
    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Usage for [day] in the device's time zone, up to now if [day] is today. */
    fun summaryFor(
        day: LocalDate,
        mode: CountingMode = CountingMode.RESUMED,
        now: Long = System.currentTimeMillis(),
    ): UsageSummary {
        val (start, end) = bounds(day, now)
        val events = readEvents(start - LOOKBACK_MILLIS, end)
        return UsageCalculator.calculate(events, start, end, excludedPackages = notCounted(), mode = mode)
    }

    /**
     * Left out of screen time and every other stat: the launcher (the home screen isn't "using an
     * app"), and Wellmute itself, so checking your screen time doesn't add to it.
     */
    private fun notCounted(): Set<String> = setOfNotNull(defaultLauncher(), context.packageName)

    /** How long [packageName] has been on screen today, including a visit still in progress. */
    fun usedTodayMillis(packageName: String, now: Long = System.currentTimeMillis()): Long =
        summaryFor(LocalDate.now(), CountingMode.VISIBLE, now).apps.find { it.packageName == packageName }?.foregroundMillis ?: 0L

    /** Usage for [day] split into hours, counting visible time (docs/decisions/0003), plus unlocks and every session. */
    fun hourlyUsageFor(day: LocalDate, now: Long = System.currentTimeMillis()): DayUsage {
        val zone = ZoneId.systemDefault()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(dayEnd, now)
        if (end <= dayStart) return DayUsage(emptyList(), 0, emptyList())

        val events = readEvents(dayStart - LOOKBACK_MILLIS, end)
        val excluded = notCounted()
        val hours = HourlyUsage.calculate(events, dayStart, dayEnd, end, excluded, CountingMode.VISIBLE)
        val whole = UsageCalculator.calculate(events, dayStart, end, excluded, CountingMode.VISIBLE)
        return DayUsage(hours, whole.unlocks, whole.sessions)
    }

    /**
     * Android's own per-app "visible" time for the same window, used only to compare against
     * our numbers while prototyping. It comes from coarse buckets, so it is a reference, not truth.
     */
    fun systemVisibleMillis(day: LocalDate, now: Long = System.currentTimeMillis()): Map<String, Long> {
        val (start, end) = bounds(day, now)
        return usageStatsManager.queryAndAggregateUsageStats(start, end).mapValues { it.value.totalTimeVisible }
    }

    private fun bounds(day: LocalDate, now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        return start to end
    }

    /**
     * The app that came to the front within the last [lookbackMillis] and is still there, from
     * Android's usage events; null if none, or if nothing changed in that time.
     */
    fun foregroundPackage(lookbackMillis: Long, now: Long = System.currentTimeMillis()): String? =
        if (!hasUsageAccess()) null else ForegroundApp.from(readEvents(now - lookbackMillis, now))

    private fun readEvents(start: Long, end: Long): List<UsageEvent> {
        val result = mutableListOf<UsageEvent>()
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Type.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEvent.Type.ACTIVITY_STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEvent.Type.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEvent.Type.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Type.DEVICE_SHUTDOWN
                else -> continue
            }
            result += UsageEvent(event.timeStamp, type, event.packageName.orEmpty(), event.className.orEmpty())
        }
        return result
    }

    /**
     * The launcher isn't "using an app", so it is left out of screen time. Only the default one:
     * Settings also registers a fallback home screen, and must still count.
     */
    private fun defaultLauncher(): String? =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

    private companion object {
        /** Read events from before the day starts, so sessions already running at midnight aren't lost. */
        const val LOOKBACK_MILLIS = 24 * 60 * 60 * 1000L

    }
}
