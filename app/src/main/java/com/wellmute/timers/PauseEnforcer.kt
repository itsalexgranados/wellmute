package com.wellmute.timers

import android.app.NotificationManager
import android.content.Context
import com.wellmute.R
import com.wellmute.apps.AppInfoRepository
import com.wellmute.data.AppTimer
import com.wellmute.data.TimerDao
import com.wellmute.bedtime.BedtimeMode
import com.wellmute.data.FocusBlockRow
import com.wellmute.data.HistoryDao
import com.wellmute.focus.FocusRepository
import com.wellmute.usage.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class PauseReason { TIMER, FOCUS, BEDTIME }

/**
 * Pauses apps, for two reasons: their daily timer has run out, or Focus mode is on and they're
 * marked as distracting. Told which app is in front by [ForegroundAppService]; while an app with
 * a timer is in front, it waits until the time left runs out, warns shortly before, and then asks
 * the service to show the pause screen.
 *
 * "Paused" isn't stored: it is worked out from today's usage and the Focus settings, so apps
 * unpause by themselves at midnight, when a timer changes, or when Focus mode ends.
 */
class PauseEnforcer(
    private val context: Context,
    private val timers: TimerDao,
    private val usage: UsageRepository,
    private val appInfo: AppInfoRepository,
    private val protectedApps: ProtectedApps,
    private val focus: FocusRepository,
    private val timerSettings: TimerSettings,
    private val history: HistoryDao,
    private val bedtime: BedtimeMode,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private data class Paused(val day: LocalDate, val packages: Set<String>)

    private val pausedByTimer = MutableStateFlow(Paused(LocalDate.MIN, emptySet()))
    private var warned = Paused(LocalDate.MIN, emptySet())
    private var foregroundPackage: String? = null
    private var watch: Job? = null

    /** Set by [ForegroundAppService] while it runs: the bubble for the 5-minute warning. */
    @Volatile
    var showBubble: ((text: String) -> Unit)? = null

    /** Set by [ForegroundAppService] while it runs: shows the pause screen over [packageName]. */
    @Volatile
    var onPause: ((packageName: String, reason: PauseReason) -> Unit)? = null

    /** Apps Focus mode pauses right now; re-emitted whenever Focus turns on or off by itself. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pausedByFocus: Flow<Set<String>> = focus.settings.flatMapLatest { settings ->
        flow {
            while (true) {
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                emit(if (settings.isActive(now, zone)) settings.apps.filterNot(protectedApps::isProtected).toSet() else emptySet())
                val next = settings.nextChange(now, zone) ?: awaitCancellation()
                delay((next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(MIN_CHECK_MILLIS))
            }
        }
    }.distinctUntilChanged()

    /**
     * Apps Bedtime blocks right now; re-emitted when Bedtime turns on or off, the list changes, or a
     * "use it for 5 minutes" runs out.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val blockedByBedtimeNow: Flow<Set<String>> =
        combine(bedtime.active, bedtime.settings, bedtime.blockedOpenUntil) { _, _, openUntil -> openUntil }.flatMapLatest { openUntil ->
            flow {
                while (true) {
                    val now = System.currentTimeMillis()
                    emit(bedtime.blockedNow(now))
                    val next = openUntil.takeIf { it > now } ?: awaitCancellation()
                    delay((next - now).coerceAtLeast(MIN_CHECK_MILLIS))
                }
            }
        }.distinctUntilChanged()

    /** Apps paused right now for any reason, for hiding their notifications. */
    val pausedPackages: Flow<Set<String>> = combine(pausedByTimer.map { it.packages }, pausedByFocus) { timer, focus -> timer + focus }

    init {
        // The warning was turned on or off: re-plan the app in front.
        scope.launch { timerSettings.warnBeforeEnd.drop(1).collect { foregroundPackage?.let(::watch) } }
        // Timers changed: an app may have just been paused or unpaused.
        scope.launch {
            timers.observeAll().collect {
                refreshPausedByTimer()
                foregroundPackage?.let(::watch)
            }
        }
        // Bedtime turned on, or a few minutes' use ran out: cover a blocked app in front.
        scope.launch {
            blockedByBedtimeNow.collect { blocked ->
                foregroundPackage?.takeIf { it in blocked }?.let { pause(it, PauseReason.BEDTIME) }
            }
        }
        // Focus mode turned on (by hand, by schedule, or at the end of a break): cover the app in front.
        scope.launch {
            pausedByFocus.collect { paused ->
                foregroundPackage?.takeIf { it in paused }?.let { pause(it, PauseReason.FOCUS) }
            }
        }
    }

    @Synchronized
    fun onForeground(packageName: String) {
        if (packageName == foregroundPackage) return
        foregroundPackage = packageName
        // When several apply, the one with no way around it comes first: a used-up daily limit
        // (Bedtime's "Use it for 5 minutes" would only lead to it), then Bedtime, then Focus.
        val today = pausedByTimer.value
        if (today.day == LocalDate.now() && packageName in today.packages) {
            watch?.cancel()
            scope.launch { pause(packageName, PauseReason.TIMER) }
            return
        }
        if (packageName in bedtime.blockedNow()) {
            watch?.cancel()
            scope.launch { pause(packageName, PauseReason.BEDTIME) }
            return
        }
        if (blockedByFocus(packageName)) {
            watch?.cancel()
            scope.launch { pause(packageName, PauseReason.FOCUS) }
            return
        }
        watch(packageName)
    }

    /** Whether [packageName] is paused right now. Timers fall back to "not paused" on a new day until recalculated. */
    fun isPaused(packageName: String): Boolean {
        if (blockedByFocus(packageName)) return true
        val current = pausedByTimer.value
        if (current.day != LocalDate.now()) {
            scope.launch { refreshPausedByTimer() }
            return false
        }
        return packageName in current.packages
    }

    private fun blockedByFocus(packageName: String) = !protectedApps.isProtected(packageName) && focus.blocks(packageName)

    @Synchronized
    private fun watch(packageName: String) {
        watch?.cancel()
        watch = scope.launch {
            if (protectedApps.isProtected(packageName)) return@launch
            val timer = timers.get(packageName) ?: return@launch
            while (true) {
                val remaining = timer.dailyLimitMillis - withContext(Dispatchers.IO) { usage.usedTodayMillis(packageName) }
                if (remaining <= 0) {
                    markPausedByTimer(packageName)
                    pause(packageName, PauseReason.TIMER)
                    return@launch
                }
                if (timerSettings.warnBeforeEnd.value && remaining <= WARNING_MILLIS) warnOnce(timer, remaining)
                val untilWarning = remaining - WARNING_MILLIS
                val shouldWarnLater = timerSettings.warnBeforeEnd.value && untilWarning > 0
                delay((if (shouldWarnLater) untilWarning else remaining).coerceAtLeast(MIN_CHECK_MILLIS))
            }
        }
    }

    private suspend fun pause(packageName: String, reason: PauseReason) = withContext(Dispatchers.Main) {
        // Each time Focus stops a distracting app, for Focus insights ("blocked attempts").
        if (reason == PauseReason.FOCUS) scope.launch(Dispatchers.IO) { history.addFocusBlock(FocusBlockRow(at = System.currentTimeMillis(), packageName = packageName)) }
        onPause?.invoke(packageName, reason)
    }

    private suspend fun refreshPausedByTimer() {
        val today = LocalDate.now()
        val packages = withContext(Dispatchers.IO) {
            timers.all()
                .filter { !protectedApps.isProtected(it.packageName) && usage.usedTodayMillis(it.packageName) >= it.dailyLimitMillis }
                .map { it.packageName }.toSet()
        }
        pausedByTimer.value = Paused(today, packages)
    }

    private fun markPausedByTimer(packageName: String) {
        val today = LocalDate.now()
        val current = pausedByTimer.value.takeIf { it.day == today } ?: Paused(today, emptySet())
        pausedByTimer.value = current.copy(packages = current.packages + packageName)
    }

    /** A bubble over the app (like Focus and usage reminders), once a day per app: "Calculator: 5 min left today". */
    private suspend fun warnOnce(timer: AppTimer, remaining: Long) {
        val today = LocalDate.now()
        if (warned.day != today) warned = Paused(today, emptySet())
        if (timer.packageName in warned.packages) return
        warned = warned.copy(packages = warned.packages + timer.packageName)
        val minutes = ((remaining + 59_999) / 60_000).toInt()
        val text = context.getString(R.string.timer_warning_bubble, appInfo.labelFor(timer.packageName), minutes)
        withContext(Dispatchers.Main) { showBubble?.invoke(text) }
    }

    private companion object {
        const val WARNING_MILLIS = 5 * 60_000L
        const val MIN_CHECK_MILLIS = 1_000L
    }
}
