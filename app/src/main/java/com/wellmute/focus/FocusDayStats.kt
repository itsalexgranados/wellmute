package com.wellmute.focus

import com.wellmute.usage.Session

/** One app's use during Focus on a day. */
data class FocusAppUse(val packageName: String, val millis: Long, val opens: Int)

/**
 * What a day's Focus time looked like: how long Focus was on, which apps were used during it (time
 * and opens), and how much of it the phone wasn't used at all, with the longest such stretch.
 */
data class FocusDayStats(
    val focusMillis: Long,
    val apps: List<FocusAppUse>,
    val idleMillis: Long,
    val longestIdleMillis: Long,
) {
    companion object {
        /** A session this soon after the app's last one is the same visit (moving between its screens). */
        private const val SAME_VISIT_GAP_MILLIS = 2_000L

        /**
         * [sessions] are the day's stretches of apps in front (the launcher already left out);
         * [focus] are the times Focus was on, as [start, end) ranges within the day.
         */
        fun of(sessions: List<Session>, focus: List<LongRange>): FocusDayStats? {
            if (focus.isEmpty()) return null
            val windows = focus.map { it.first to it.last + 1 }.sortedBy { it.first }
            val focusMillis = windows.sumOf { (start, end) -> end - start }

            // Each session clipped to each Focus window it overlaps.
            val clipped = sessions.flatMap { s ->
                windows.mapNotNull { (start, end) ->
                    val from = maxOf(s.start, start)
                    val to = minOf(s.end, end)
                    if (to > from) Session(s.packageName, from, to) else null
                }
            }.sortedBy { it.start }

            val apps = clipped.groupBy { it.packageName }.map { (pkg, parts) ->
                // Opens: visits that began during Focus, joining an app's back-to-back stretches.
                var opens = 0
                var lastEnd: Long? = null
                for (part in parts.sortedBy { it.start }) {
                    val startedInFocus = sessions.any { it.packageName == pkg && it.start == part.start }
                    val newVisit = lastEnd.let { it == null || part.start - it > SAME_VISIT_GAP_MILLIS }
                    if (startedInFocus && newVisit) opens++
                    lastEnd = part.end
                }
                FocusAppUse(pkg, parts.sumOf { it.millis }, opens)
            }.filter { it.millis > 0 || it.opens > 0 }.sortedByDescending { it.millis }

            // Phone-free time: each Focus window minus the time any app was in front.
            var idle = 0L
            var longest = 0L
            for ((start, end) in windows) {
                var cursor = start
                for (part in clipped.filter { it.start < end && it.end > start }) {
                    if (part.start > cursor) {
                        val gap = part.start - cursor
                        idle += gap
                        longest = maxOf(longest, gap)
                    }
                    cursor = maxOf(cursor, part.end)
                }
                if (end > cursor) {
                    idle += end - cursor
                    longest = maxOf(longest, end - cursor)
                }
            }
            return FocusDayStats(focusMillis, apps, idle, longest)
        }
    }
}
