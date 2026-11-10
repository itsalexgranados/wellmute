package com.wellmute.focus

import com.wellmute.data.HistoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId

/** How Focus went over some days (see [FocusInsightsRepository]). */
data class FocusInsights(
    /** Time Focus mode was on. */
    val focusMillis: Long,
    /** The part of it the phone wasn't used, and the longest such stretch. */
    val idleMillis: Long,
    val longestIdleMillis: Long,
    val breaks: Int,
    val breakMillis: Long,
    /** Breaks cut short: ended by hand, or by turning Focus on or off. */
    val breaksEndedEarly: Int,
    /** Apps used during Focus, most time first. */
    val apps: List<FocusAppUse>,
    /** Times a distracting app was stopped by the pause screen, and which, most first. */
    val blocked: Int,
    val blockedApps: List<Pair<String, Int>>,
) {
    val isEmpty: Boolean get() = focusMillis == 0L && breaks == 0 && blocked == 0
}

/** Reads Focus insights from what Wellmute saved: each day's Focus stats, breaks and blocked attempts. */
class FocusInsightsRepository(private val dao: HistoryDao, private val ownPackage: String) {

    fun observe(from: LocalDate, to: LocalDate, now: Long = System.currentTimeMillis()): Flow<FocusInsights> {
        val zone = ZoneId.systemDefault()
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return combine(
            dao.observeDayTotalRows(from.toEpochDay(), to.toEpochDay()),
            dao.observeFocusAppDays(from.toEpochDay(), to.toEpochDay()),
            dao.observeFocusBreaks(fromMillis, toMillis),
            dao.observeFocusBlocks(fromMillis, toMillis),
        ) { days, appDays, breaks, blocks ->
            FocusInsights(
                focusMillis = days.sumOf { it.focusMillis ?: 0L },
                idleMillis = days.sumOf { it.focusIdleMillis ?: 0L },
                longestIdleMillis = days.maxOfOrNull { it.focusLongestIdleMillis ?: 0L } ?: 0L,
                breaks = breaks.size,
                // A break still running counts up to now.
                breakMillis = breaks.sumOf { (minOf(it.endedAt ?: it.plannedEnd, now) - it.start).coerceAtLeast(0) },
                breaksEndedEarly = breaks.count { it.endedAt != null && it.endedAt < it.plannedEnd },
                // Wellmute's own screens (Focus, the pause screen) aren't "an app you used".
                apps = appDays.filter { it.packageName != ownPackage }.groupBy { it.packageName }
                    .map { (pkg, rows) -> FocusAppUse(pkg, rows.sumOf { it.millis }, rows.sumOf { it.opens }) }
                    .sortedByDescending { it.millis },
                blocked = blocks.size,
                blockedApps = blocks.groupingBy { it.packageName }.eachCount().toList().sortedByDescending { it.second },
            )
        }
    }
}
