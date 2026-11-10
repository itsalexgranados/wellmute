package com.wellmute.reminders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wellmute.R
import com.wellmute.apps.AppInfoRepository
import com.wellmute.timers.ProtectedApps
import com.wellmute.usage.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private val Context.remindersStore: DataStore<Preferences> by preferencesDataStore(name = "reminders")

data class LastReminder(val appLabel: String, val minutes: Int, val atMillis: Long, val bubbleShown: Boolean)

data class ReminderSettings(val enabled: Boolean = false, val intervalMinutes: Int = DEFAULT_INTERVAL) {
    companion object {
        const val DEFAULT_INTERVAL = 5
        val INTERVALS = listOf(5, 10, 15, 30, 60)
    }
}

/**
 * Optional reminders while an app is open, as a small bubble floating over it ([ReminderBubble]):
 * "Calculator · 10 min today", at every multiple of the interval of that app's
 * total screen time today. Off by default.
 *
 * Told which app is in front by the accessibility service, like app timers. To cost almost no
 * battery it never polls: each time an app comes to the front it reads today's total once and waits
 * for the next mark, and it stops while the screen is off. The count is the day's total, so it
 * carries on across visits.
 */
class UsageReminders(
    private val context: Context,
    private val appInfo: AppInfoRepository,
    private val protectedApps: ProtectedApps,
    private val usage: UsageRepository,
    private val scope: CoroutineScope,
) {
    private val store = context.remindersStore

    val settings: StateFlow<ReminderSettings> = store.data
        .map { ReminderSettings(it[ENABLED] ?: false, it[INTERVAL] ?: ReminderSettings.DEFAULT_INTERVAL) }
        .stateIn(scope, SharingStarted.Eagerly, ReminderSettings())

    private var foregroundPackage: String? = null
    private var screenOn = true
    private var job: Job? = null

    /** The last reminder shown, for the settings screen: helps tell "never fires" from "can't be seen". Not saved. */
    val lastReminder = MutableStateFlow<LastReminder?>(null)

    /** The last mark shown per app today, so none repeats. */
    private val reminded = ConcurrentHashMap<String, Pair<LocalDate, Int>>()

    /** Set by the accessibility service while it runs: floats a reminder over the app in front. */
    @Volatile
    var showBubble: ((text: String) -> Unit)? = null

    init {
        // Turned on, off or a new interval: start the current visit's count again.
        scope.launch { settings.drop(1).collect { restart() } }
    }

    suspend fun setEnabled(enabled: Boolean) = store.edit { it[ENABLED] = enabled }

    suspend fun setInterval(minutes: Int) = store.edit { it[INTERVAL] = minutes }

    @Synchronized
    fun onForeground(packageName: String) {
        // An app coming to the front means the phone is in use, even if the "unlocked" broadcast
        // never came (it depends on the lock screen): without this, reminders could stay paused for
        // good after the screen first turned off.
        val wasPaused = !screenOn
        screenOn = true
        if (packageName == foregroundPackage && !wasPaused) return
        foregroundPackage = packageName
        restart()
    }

    @Synchronized
    fun onScreenOff() {
        screenOn = false
        restart()
    }

    /** The phone was unlocked: whatever is in front starts a new visit. */
    @Synchronized
    fun onUnlocked() {
        screenOn = true
        restart()
    }

    @Synchronized
    private fun restart() {
        job?.cancel()
        job = null
        val packageName = foregroundPackage ?: return
        val current = settings.value
        if (!current.enabled || !screenOn) return
        // Not for Wellmute itself, the launcher, the phone app or anything else that's always allowed.
        if (packageName == context.packageName || protectedApps.isProtected(packageName)) return
        job = scope.launch {
            val interval = current.intervalMinutes * 60_000L
            while (true) {
                // Today's total for this app, including this visit, so the count carries on across
                // visits: 5 minutes now and 5 more later is a reminder at 10, not two at 5.
                val used = withContext(Dispatchers.IO) { usage.usedTodayMillis(packageName) }
                val nextMark = (used / interval + 1) * interval
                delay((nextMark - used).coerceAtLeast(MIN_WAIT_MILLIS))
                val now = withContext(Dispatchers.IO) { usage.usedTodayMillis(packageName) }
                if (now >= nextMark - TOLERANCE_MILLIS) remindOnce(packageName, (nextMark / 60_000L).toInt())
            }
        }
    }

    /** Each mark once a day per app, even if the app is opened and closed around it. */
    private suspend fun remindOnce(packageName: String, minutes: Int) {
        val today = LocalDate.now()
        val last = reminded[packageName]?.takeIf { it.first == today }?.second ?: 0
        if (minutes <= last) return
        reminded[packageName] = today to minutes
        remind(packageName, minutes)
    }

    private suspend fun remind(packageName: String, minutes: Int) {
        val label = appInfo.labelFor(packageName)
        val text = context.getString(R.string.reminders_bubble, label, durationText(minutes))
        val shown = withContext(Dispatchers.Main) { showBubble?.let { it(text); true } ?: false }
        lastReminder.value = LastReminder(label, minutes, System.currentTimeMillis(), shown)
    }

    /** "10 min", "1 h", "1 h 10 min", like the rest of the app. */
    private fun durationText(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> context.getString(R.string.duration_minutes, rest)
            rest == 0 -> context.getString(R.string.duration_hours, hours)
            else -> context.getString(R.string.duration_hours_minutes, hours, rest)
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val INTERVAL = intPreferencesKey("interval_minutes")
        const val MIN_WAIT_MILLIS = 1_000L

        /** Usage figures can lag a moment behind the clock. */
        const val TOLERANCE_MILLIS = 2_000L
    }
}
