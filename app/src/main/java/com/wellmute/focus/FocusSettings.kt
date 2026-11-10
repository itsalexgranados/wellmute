package com.wellmute.focus

import com.wellmute.schedule.WeeklySchedule
import java.time.Instant
import java.time.ZoneId

/**
 * Everything that decides whether Focus mode is on. Nothing here changes over time by itself:
 * whether it is on is worked out for a given moment, so schedules and breaks need no alarms.
 */
data class FocusSettings(
    /** Turned on by the user; stays on until turned off. */
    val manualOn: Boolean = false,
    /** Turned off by the user during a scheduled window: stays off until this time (epoch millis). */
    val manualOffUntil: Long = 0,
    /** On a break until this time (epoch millis). */
    val breakUntil: Long = 0,
    val schedule: WeeklySchedule = WeeklySchedule(),
    /** A bubble a minute before a break ends, so the paused apps don't come back as a surprise. */
    val warnBeforeBreakEnds: Boolean = true,
    /** The distracting apps paused while Focus mode is on. */
    val apps: Set<String> = emptySet(),
) {
    /** Focus mode would be on if it weren't for a break. */
    fun isScheduledOrOn(now: Instant, zone: ZoneId): Boolean {
        if (manualOn) return true
        return schedule.windowAt(now.atZone(zone)) != null && now.toEpochMilli() >= manualOffUntil
    }

    fun isActive(now: Instant, zone: ZoneId): Boolean = !isOnBreak(now) && isScheduledOrOn(now, zone)

    fun isOnBreak(now: Instant): Boolean = now.toEpochMilli() < breakUntil

    /** The next moment [isActive] may change, so pausing can be re-checked exactly then. */
    fun nextChange(now: Instant, zone: ZoneId): Instant? = listOfNotNull(
        breakUntil.takeIf { it > now.toEpochMilli() }?.let(Instant::ofEpochMilli),
        manualOffUntil.takeIf { it > now.toEpochMilli() }?.let(Instant::ofEpochMilli),
        schedule.nextBoundaryAfter(now.atZone(zone))?.toInstant(),
    ).minOrNull()

    fun turnedOn(): FocusSettings = copy(manualOn = true, manualOffUntil = 0, breakUntil = 0)

    /** Off now; if a scheduled window is running, it stays off until that window ends. */
    fun turnedOff(now: Instant, zone: ZoneId): FocusSettings {
        val window = schedule.windowAt(now.atZone(zone))
        return copy(manualOn = false, breakUntil = 0, manualOffUntil = window?.endInclusive?.toInstant()?.toEpochMilli() ?: 0)
    }

    fun onBreak(now: Instant, minutes: Int): FocusSettings = copy(breakUntil = now.toEpochMilli() + minutes * 60_000L)
}
