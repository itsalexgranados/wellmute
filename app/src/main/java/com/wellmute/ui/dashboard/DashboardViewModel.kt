package com.wellmute.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.data.AppDay
import com.wellmute.data.AppTimer
import com.wellmute.data.Bucket
import com.wellmute.data.DaySummary
import com.wellmute.data.HistoryRepository
import com.wellmute.data.Period
import com.wellmute.data.PeriodSummary
import com.wellmute.data.PeriodType
import com.wellmute.data.TRACKED_DAY_MILLIS
import com.wellmute.data.TimerDao
import com.wellmute.data.WeekDay
import com.wellmute.data.countedDays
import com.wellmute.permissions.PermissionChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale

class DashboardViewModel(
    private val history: HistoryRepository,
    private val timers: TimerDao,
    private val permissions: PermissionChecker,
) : ViewModel() {

    data class Access(val usage: Boolean, val notifications: Boolean, val accessibility: Boolean)

    data class State(
        val summary: DaySummary,
        /** Screen time of the 7 days ending on the selected day, oldest first. */
        val week: List<WeekDay>,
        val timers: Map<String, AppTimer>,
        /** The oldest day in history, or null when there's none yet. */
        val firstDay: LocalDate?,
        val access: Access,
        /** Screen time on the same weekday a week before, to compare with; null when there's no fair one. */
        val sameDayLastWeekMillis: Long? = null,
    ) {
        val canGoBack: Boolean get() = firstDay != null && summary.day > firstDay
        val canGoForward: Boolean get() = summary.day < LocalDate.now()
    }

    /** A tapped bar: one day of a week or month, or one month of a year. */
    data class Selection(val bucket: Bucket, val apps: List<AppDay>, val unlocks: Int, val days: Int = 1) {
        val totalMillis: Long get() = apps.sumOf { it.foregroundMillis }
        val totalNotifications: Int get() = apps.sumOf { it.notifications }
        val averageMillis: Long get() = if (days == 0) 0 else totalMillis / days
    }

    data class PeriodState(val summary: PeriodSummary, val firstDay: LocalDate?, val selection: Selection? = null) {
        val canGoBack: Boolean get() = firstDay != null && firstDay < summary.period.start
        val canGoForward: Boolean get() = summary.period.end < LocalDate.now()
    }

    private val day = MutableStateFlow(LocalDate.now())
    private val access = MutableStateFlow(readAccess())

    /** Null while the day view is shown. */
    private val period = MutableStateFlow<Period?>(null)
    private val selectedBucket = MutableStateFlow<Bucket?>(null)

    init {
        // A tapped bar belongs to the period it was tapped in.
        viewModelScope.launch { period.collect { selectedBucket.value = null } }
    }

    val view: StateFlow<PeriodType?> = period.map { it?.type }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State?> = day.flatMapLatest { selected ->
        combine(
            history.observeDay(selected),
            // Today is compared up to the current hour; other days whole.
            combine(history.observeWeek(selected), history.observeDayTotal(selected.minusDays(7), untilHourFor(selected)), ::Pair),
            history.observeFirstDay(),
            timers.observeAll(),
            access,
        ) { summary, (week, lastWeek), first, timerList, access ->
            // The first day of history is partial (Wellmute was installed during it): not a fair comparison.
            val comparable = first != null && selected.minusDays(7) > first && lastWeek >= TRACKED_DAY_MILLIS
            State(summary, week, timerList.associateBy { it.packageName }, first, access, lastWeek.takeIf { comparable })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val periodState: StateFlow<PeriodState?> = combine(period, selectedBucket, ::Pair).flatMapLatest { (selected, bucket) ->
        if (selected == null) {
            flowOf(null)
        } else {
            val selection = if (bucket == null) {
                flowOf(null)
            } else {
                history.observeRange(bucket.start, bucket.end).map { (apps, unlocks) -> Selection(bucket, apps, unlocks) }
            }
            combine(history.observePeriod(selected), history.observeFirstDay(), selection) { summary, first, picked ->
                val days = picked?.let { countedDays(it.bucket.start, it.bucket.end, first, LocalDate.now()) } ?: 0
                PeriodState(summary, first, picked?.copy(days = days))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shows one bar's numbers in place of the period's; tapping the selected bar again clears it. */
    fun selectBucket(bucket: Bucket?) {
        selectedBucket.value = if (bucket == selectedBucket.value) null else bucket
    }

    /** Re-reads permissions and recalculates the selected day, which may still be changing. */
    fun refresh() {
        access.value = readAccess()
        viewModelScope.launch {
            history.syncDay(day.value)
            if (day.value != LocalDate.now()) history.syncDay(LocalDate.now())
        }
    }

    fun saveTimer(packageName: String, dailyLimitMillis: Long) {
        viewModelScope.launch { timers.save(AppTimer(packageName, dailyLimitMillis)) }
    }

    fun deleteTimer(packageName: String) {
        viewModelScope.launch { timers.delete(packageName) }
    }

    private fun readAccess() = Access(permissions.hasUsageAccess(), permissions.hasNotificationAccess(), permissions.hasAccessibility())

    fun previousDay() = select(day.value.minusDays(1))

    fun nextDay() = select(day.value.plusDays(1))

    /** Shows [newDay] in the day view. */
    fun select(newDay: LocalDate) {
        if (newDay > LocalDate.now()) return
        period.value = null
        day.value = newDay
        refresh()
    }

    /** Switches to the week, month or year around the day being looked at; null goes back to days. */
    fun showView(type: PeriodType?) {
        if (type == null) {
            period.value?.let { select(minOf(it.end, LocalDate.now())) }
            return
        }
        val anchor = period.value?.let { minOf(it.end, LocalDate.now()) } ?: day.value
        showPeriod(type, anchor)
    }

    /** Shows the period of [type] that contains [day]. */
    fun showPeriod(type: PeriodType, day: LocalDate) {
        period.value = Period.containing(type, day, firstDayOfWeek())
    }

    fun previousPeriod() {
        period.value = period.value?.previous()
    }

    fun nextPeriod() {
        period.value = period.value?.next()?.takeIf { it.start <= LocalDate.now() }
            ?: period.value
    }

    private fun firstDayOfWeek() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /** For today, the hour after the current one (so the hour in progress counts); null for past days. */
    private fun untilHourFor(day: LocalDate): Int? = if (day == LocalDate.now()) LocalTime.now().hour + 1 else null

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as WellmuteApp).container
                DashboardViewModel(container.history, container.timers, container.permissions)
            }
        }
    }
}
