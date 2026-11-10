package com.wellmute.usage

/**
 * The subset of Android usage events the calculator needs. Kept free of Android types so the
 * calculation can be unit-tested on the JVM; [UsageRepository] maps the real events to these.
 */
data class UsageEvent(
    val timestamp: Long,
    val type: Type,
    val packageName: String = "",
    val className: String = "",
) {
    enum class Type {
        ACTIVITY_RESUMED,
        ACTIVITY_PAUSED,
        ACTIVITY_STOPPED,
        SCREEN_NON_INTERACTIVE,
        KEYGUARD_HIDDEN,
        DEVICE_SHUTDOWN,
    }
}

data class AppUsage(
    val packageName: String,
    val foregroundMillis: Long,
    val opens: Int,
)

/** One continuous stretch of an app in front, clipped to the window that was calculated. */
data class Session(val packageName: String, val start: Long, val end: Long) {
    val millis: Long get() = end - start
}

data class UsageSummary(
    /** Sorted by foreground time, longest first. */
    val apps: List<AppUsage>,
    val unlocks: Int,
    /** Every stretch of use, in order, for the longest session and use during Bedtime. */
    val sessions: List<Session> = emptyList(),
) {
    val totalMillis: Long get() = apps.sumOf { it.foregroundMillis }
}

/** When an app's time stops counting. Phase 2 compares both against the system's numbers. */
enum class CountingMode {
    /** Only while one of its activities is resumed: in front and taking input. */
    RESUMED,

    /** Until it is stopped, so it keeps counting behind a dialog from another app. */
    VISIBLE,
}

/**
 * Turns a stream of usage events into per-app foreground time, opens and unlocks for the
 * window [start, end).
 *
 * Activities are counted per package, not by class name: apps like Settings show many screens
 * that share one class, and the public API doesn't expose which instance an event belongs to.
 * Resumes are paired with pauses, so the count of resumed activities stays exact; when a phone
 * logs a stop without the pause before it, the stop counts as that pause.
 *
 * A package counts while any of its activities is resumed, so split screen counts both apps.
 * In [CountingMode.VISIBLE] it keeps counting after its last pause until it is stopped, or
 * until it resumes again (a dialog on top went away). Turning the screen off ends every session,
 * since some apps never report a pause in that case. Pass events from before [start] too:
 * sessions that began earlier are clipped to the window instead of being lost.
 */
object UsageCalculator {

    /**
     * Moving between activities of one app pauses the old one before resuming the new one.
     * A resume this soon after the app's last session ended continues the visit instead of
     * counting a new open.
     */
    private const val SAME_VISIT_GAP_MILLIS = 2_000L

    fun calculate(
        events: List<UsageEvent>,
        start: Long,
        end: Long,
        excludedPackages: Set<String> = emptySet(),
        mode: CountingMode = CountingMode.RESUMED,
    ): UsageSummary {
        val resumedCounts = mutableMapOf<String, Int>()
        // Per activity class: how many are resumed, and how many were paused but not stopped yet.
        // Some phones log an activity going from resumed straight to stopped, with no pause in
        // between; a stop with no pause pending stands in for that missing pause.
        val resumedByClass = mutableMapOf<Pair<String, String>, Int>()
        val pausedByClass = mutableMapOf<Pair<String, String>, Int>()
        val sessionStarts = mutableMapOf<String, Long>()
        // Packages with nothing resumed that still count as visible until they stop (VISIBLE mode).
        val pausedButVisible = mutableSetOf<String>()
        val lastSessionEnds = mutableMapOf<String, Long>()
        val foreground = mutableMapOf<String, Long>()
        val opens = mutableMapOf<String, Int>()
        val sessions = mutableListOf<Session>()
        var unlocks = 0

        fun closeSession(packageName: String, at: Long) {
            pausedButVisible -= packageName
            val sessionStart = sessionStarts.remove(packageName) ?: return
            lastSessionEnds[packageName] = at
            val overlap = minOf(at, end) - maxOf(sessionStart, start)
            if (overlap > 0) {
                foreground.merge(packageName, overlap, Long::plus)
                if (packageName !in excludedPackages) sessions += Session(packageName, maxOf(sessionStart, start), minOf(at, end))
            }
        }

        fun closeAll(at: Long) {
            sessionStarts.keys.toList().forEach { closeSession(it, at) }
            resumedCounts.clear()
            resumedByClass.clear()
            pausedByClass.clear()
            lastSessionEnds.clear()
        }

        /** One fewer resumed activity of [pkg]; with none left, what happens depends on [mode]. */
        fun onPaused(pkg: String, at: Long, stopped: Boolean) {
            val count = resumedCounts[pkg] ?: 0
            resumedCounts[pkg] = count - 1
            if (count == 1) {
                if (stopped || mode == CountingMode.RESUMED) closeSession(pkg, at) else pausedButVisible += pkg
            }
        }

        for (event in events.sortedBy { it.timestamp }) {
            if (event.timestamp >= end) break
            val pkg = event.packageName
            val activity = pkg to event.className
            when (event.type) {
                UsageEvent.Type.ACTIVITY_RESUMED -> {
                    if (pkg !in sessionStarts) {
                        sessionStarts[pkg] = event.timestamp
                        val lastEnd = lastSessionEnds.remove(pkg)
                        val sameVisit = lastEnd != null && event.timestamp - lastEnd <= SAME_VISIT_GAP_MILLIS
                        if (event.timestamp >= start && !sameVisit) opens.merge(pkg, 1, Int::plus)
                    }
                    pausedButVisible -= pkg
                    resumedCounts.merge(pkg, 1, Int::plus)
                    resumedByClass.merge(activity, 1, Int::plus)
                }

                UsageEvent.Type.ACTIVITY_PAUSED -> {
                    if ((resumedCounts[pkg] ?: 0) == 0) continue // Its resume happened before the events we read.
                    resumedByClass.computeIfPresent(activity) { _, n -> (n - 1).takeIf { it > 0 } }
                    pausedByClass.merge(activity, 1, Int::plus)
                    onPaused(pkg, event.timestamp, stopped = false)
                }

                UsageEvent.Type.ACTIVITY_STOPPED -> when {
                    // The usual order: paused, then stopped.
                    (pausedByClass[activity] ?: 0) > 0 -> {
                        pausedByClass.computeIfPresent(activity) { _, n -> (n - 1).takeIf { it > 0 } }
                        if (pkg in pausedButVisible) closeSession(pkg, event.timestamp)
                    }
                    // Stopped while still resumed: the pause was never logged.
                    (resumedByClass[activity] ?: 0) > 0 -> {
                        resumedByClass.computeIfPresent(activity) { _, n -> (n - 1).takeIf { it > 0 } }
                        onPaused(pkg, event.timestamp, stopped = true)
                    }
                    else -> if (pkg in pausedButVisible) closeSession(pkg, event.timestamp)
                }

                UsageEvent.Type.SCREEN_NON_INTERACTIVE, UsageEvent.Type.DEVICE_SHUTDOWN -> closeAll(event.timestamp)

                UsageEvent.Type.KEYGUARD_HIDDEN -> if (event.timestamp >= start) unlocks++
            }
        }
        closeAll(end)

        val apps = (foreground.keys + opens.keys)
            .filterNot { it in excludedPackages }
            .map { AppUsage(it, foreground[it] ?: 0L, opens[it] ?: 0) }
            .sortedByDescending { it.foregroundMillis }
        return UsageSummary(apps, unlocks, sessions.sortedBy { it.start })
    }
}
