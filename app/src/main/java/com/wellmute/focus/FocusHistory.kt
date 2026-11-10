package com.wellmute.focus

import com.wellmute.data.FocusPeriodRow
import com.wellmute.data.HistoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Remembers when Focus mode was on (by hand, on a schedule; breaks excluded), so the dashboard can
 * show how much the phone was used during it. Each stretch is saved as it starts and closed as it
 * ends, re-checked exactly when Focus may turn on or off by itself.
 *
 * If Wellmute isn't running when Focus ends, the stretch is closed the next time it starts, so it
 * can come out a little long; [FocusSettings.nextChange] keeps that rare.
 */
class FocusHistory(private val dao: HistoryDao, focus: FocusRepository, scope: CoroutineScope) {

    init {
        scope.launch(Dispatchers.IO) {
            focus.settings.collectLatest { settings ->
                while (true) {
                    val now = Instant.now()
                    record(settings.isActive(now, ZoneId.systemDefault()), now.toEpochMilli())
                    val next = settings.nextChange(now, ZoneId.systemDefault()) ?: awaitCancellation()
                    delay((next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(MIN_CHECK_MILLIS))
                }
            }
        }
    }

    private suspend fun record(active: Boolean, now: Long) {
        val open = dao.openFocusPeriod()
        when {
            active && open == null -> dao.saveFocusPeriod(FocusPeriodRow(now, null))
            !active && open != null -> dao.saveFocusPeriod(open.copy(end = now))
        }
    }

    private companion object {
        const val MIN_CHECK_MILLIS = 1_000L
    }
}
