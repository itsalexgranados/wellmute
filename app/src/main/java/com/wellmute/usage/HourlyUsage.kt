package com.wellmute.usage

/** Usage of every app during one hour of a day. */
data class HourUsage(
    /** Hours since local midnight. Clock-change days have 23 or 25 of them. */
    val hour: Int,
    val apps: List<AppUsage>,
)

object HourlyUsage {

    const val HOUR_MILLIS = 60 * 60 * 1000L

    /**
     * Splits the day starting at [dayStart] into hours and calculates each one, up to [end]
     * (the end of the day, or now for today). Each hour reuses the same events, so a session
     * crossing an hour is split between both and its open counts only in the hour it started.
     */
    fun calculate(
        events: List<UsageEvent>,
        dayStart: Long,
        dayEnd: Long,
        end: Long,
        excludedPackages: Set<String> = emptySet(),
        mode: CountingMode = CountingMode.VISIBLE,
    ): List<HourUsage> {
        val hours = ((dayEnd - dayStart) / HOUR_MILLIS).toInt()
        return (0 until hours).mapNotNull { hour ->
            val hourStart = dayStart + hour * HOUR_MILLIS
            val hourEnd = minOf(hourStart + HOUR_MILLIS, end)
            if (hourStart >= hourEnd) return@mapNotNull null
            val apps = UsageCalculator.calculate(events, hourStart, hourEnd, excludedPackages, mode).apps
            HourUsage(hour, apps).takeIf { apps.isNotEmpty() }
        }
    }
}

/** One day of usage: by hour, the unlocks, and every session of the whole day. */
data class DayUsage(val hours: List<HourUsage>, val unlocks: Int, val sessions: List<Session>)
