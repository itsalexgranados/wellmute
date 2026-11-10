package com.wellmute.focus

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wellmute.data.FocusBreakRow
import com.wellmute.data.HistoryDao
import com.wellmute.schedule.WeeklySchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

private val Context.focusStore: DataStore<Preferences> by preferencesDataStore(name = "focus")

/** Focus mode settings, kept in DataStore and always available as the latest value. */
class FocusRepository(context: Context, scope: CoroutineScope, private val dao: HistoryDao) {

    private val store = context.focusStore

    val settings: StateFlow<FocusSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, FocusSettings())

    fun isActive(now: Instant = Instant.now()): Boolean = settings.value.isActive(now, ZoneId.systemDefault())

    /** Whether Focus mode pauses [packageName] right now. */
    fun blocks(packageName: String, now: Instant = Instant.now()): Boolean =
        packageName in settings.value.apps && isActive(now)

    suspend fun turnOn() {
        closeRunningBreak()
        update { it.turnedOn() }
    }

    suspend fun turnOff() {
        closeRunningBreak()
        update { it.turnedOff(Instant.now(), ZoneId.systemDefault()) }
    }

    /** Also remembered for Focus insights (how many breaks, how long). */
    suspend fun takeBreak(minutes: Int) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { dao.saveFocusBreak(FocusBreakRow(now, now + minutes * 60_000L)) }
        update { it.onBreak(Instant.ofEpochMilli(now), minutes) }
    }

    suspend fun endBreak() {
        closeRunningBreak()
        update { it.copy(breakUntil = 0) }
    }

    /** A break cut short (ended, or Focus turned on or off by hand) is saved as ending now. */
    private suspend fun closeRunningBreak() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.runningFocusBreak(now)?.let { dao.saveFocusBreak(it.copy(endedAt = now)) }
    }

    suspend fun setApp(packageName: String, distracting: Boolean) =
        update { it.copy(apps = if (distracting) it.apps + packageName else it.apps - packageName) }

    suspend fun setSchedule(schedule: WeeklySchedule) = update { it.copy(schedule = schedule) }

    suspend fun setWarnBeforeBreakEnds(on: Boolean) = update { it.copy(warnBeforeBreakEnds = on) }

    private suspend fun update(change: (FocusSettings) -> FocusSettings) {
        store.edit { prefs -> change(prefs.toSettings()).writeTo(prefs) }
    }

    private fun Preferences.toSettings() = FocusSettings(
        manualOn = this[MANUAL_ON] ?: false,
        manualOffUntil = this[MANUAL_OFF_UNTIL] ?: 0,
        breakUntil = this[BREAK_UNTIL] ?: 0,
        schedule = WeeklySchedule(
            enabled = this[SCHEDULE_ENABLED] ?: false,
            days = this[SCHEDULE_DAYS]?.mapNotNull { name -> DayOfWeek.entries.find { it.name == name } }?.toSet()
                ?: WeeklySchedule().days,
            startMinute = this[SCHEDULE_START] ?: WeeklySchedule().startMinute,
            endMinute = this[SCHEDULE_END] ?: WeeklySchedule().endMinute,
        ),
        apps = this[APPS] ?: emptySet(),
        warnBeforeBreakEnds = this[WARN_BEFORE_BREAK_ENDS] ?: true,
    )

    private fun FocusSettings.writeTo(prefs: MutablePreferences) {
        prefs[MANUAL_ON] = manualOn
        prefs[MANUAL_OFF_UNTIL] = manualOffUntil
        prefs[BREAK_UNTIL] = breakUntil
        prefs[SCHEDULE_ENABLED] = schedule.enabled
        prefs[SCHEDULE_DAYS] = schedule.days.map { it.name }.toSet()
        prefs[SCHEDULE_START] = schedule.startMinute
        prefs[SCHEDULE_END] = schedule.endMinute
        prefs[APPS] = apps
        prefs[WARN_BEFORE_BREAK_ENDS] = warnBeforeBreakEnds
    }

    private companion object {
        val MANUAL_ON = booleanPreferencesKey("manual_on")
        val MANUAL_OFF_UNTIL = longPreferencesKey("manual_off_until")
        val BREAK_UNTIL = longPreferencesKey("break_until")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_DAYS = stringSetPreferencesKey("schedule_days")
        val SCHEDULE_START = intPreferencesKey("schedule_start")
        val SCHEDULE_END = intPreferencesKey("schedule_end")
        val APPS = stringSetPreferencesKey("apps")
        val WARN_BEFORE_BREAK_ENDS = booleanPreferencesKey("warn_before_break_ends")
    }
}
