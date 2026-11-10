package com.wellmute.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.data.AppTimer
import com.wellmute.data.HistoryRepository
import com.wellmute.data.Metric
import com.wellmute.data.Period
import com.wellmute.data.PeriodType
import com.wellmute.data.TimerDao
import com.wellmute.data.TRACKED_DAY_MILLIS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * One app over time, like the dashboard: a day by hour, or a week, month or year, for screen
 * time, notifications or opens. A tapped bar's value shows in the header without moving anything.
 */
class AppDetailViewModel(
    savedState: SavedStateHandle,
    private val history: HistoryRepository,
    private val timers: TimerDao,
) : ViewModel() {

    data class State(
        val metric: Metric,
        /** The day shown when [period] is null (the day view). */
        val day: LocalDate,
        val period: Period?,
        /** Hours of [day], or one bar per day (per month for a year). */
        val bars: List<Long>,
        val total: Long,
        /** Daily average over the period; null in the day view. */
        val dailyAverage: Long?,
        val firstDay: LocalDate?,
        val selectedIndex: Int?,
        /** To compare with: the period before's daily average, or the same weekday last week. */
        val previous: Long? = null,
    ) {
        val canGoBack: Boolean
            get() = firstDay != null && if (period == null) day > firstDay else firstDay < period.start
        val canGoForward: Boolean
            get() = if (period == null) day < LocalDate.now() else period.end < LocalDate.now()
    }

    private data class Query(val metric: Metric, val day: LocalDate, val period: Period?)

    val packageName: String = checkNotNull(savedState[ARG_PACKAGE])
    private val query = MutableStateFlow(
        LocalDate.ofEpochDay(checkNotNull(savedState.get<Long>(ARG_DAY))).let { day ->
            // Opened from the dashboard's week, month or year: show the same one.
            val view = savedState.get<String>(ARG_VIEW)?.let(PeriodType::valueOf)
            Query(Metric.SCREEN_TIME, day, view?.let { Period.containing(it, day, WeekFields.of(Locale.getDefault()).firstDayOfWeek) })
        },
    )
    private val selected = MutableStateFlow<Int?>(null)

    val view: StateFlow<PeriodType?> = query.map { it.period?.type }.stateIn(viewModelScope, SharingStarted.Eagerly, query.value.period?.type)

    private data class Values(val bars: List<Long>, val total: Long, val average: Long?, val previous: Long?)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State?> = query.flatMapLatest { q ->
        val values = if (q.period == null) {
            dayValues(q.day, q.metric)
        } else {
            history.observeAppPeriod(packageName, q.period, q.metric).map { p -> Values(p.bars, p.total, p.dailyAverage, p.previousAverage) }
        }
        combine(values, history.observeFirstDay(), selected) { v, first, index ->
            State(q.metric, q.day, q.period, v.bars, v.total, v.average, first, index?.takeIf { it in v.bars.indices }, v.previous)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * A day by hour, and the same weekday a week before to compare with, like the dashboard's day:
     * today only up to the current hour, and only when that day was fully tracked.
     */
    private fun dayValues(day: LocalDate, metric: Metric) = combine(
        history.observeAppHours(packageName, day, metric),
        history.observeAppHours(packageName, day.minusDays(7), metric),
        history.observeDayTotal(day.minusDays(7)),
        history.observeFirstDay(),
    ) { hours, lastWeekHours, lastWeekScreenTime, first ->
        val untilHour = if (day == LocalDate.now()) LocalTime.now().hour + 1 else lastWeekHours.size
        val comparable = first != null && day.minusDays(7) > first && lastWeekScreenTime >= TRACKED_DAY_MILLIS
        Values(hours, hours.sum(), null, lastWeekHours.take(untilHour).sum().takeIf { comparable })
    }

    val timer: StateFlow<AppTimer?> = timers.observeAll()
        .map { all -> all.find { it.packageName == packageName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveTimer(dailyLimitMillis: Long) {
        viewModelScope.launch { timers.save(AppTimer(packageName, dailyLimitMillis)) }
    }

    fun deleteTimer() {
        viewModelScope.launch { timers.delete(packageName) }
    }

    fun selectMetric(metric: Metric) = update { it.copy(metric = metric) }

    /** Shows the day view on [day]. */
    fun selectDay(day: LocalDate) {
        if (day <= LocalDate.now()) update { it.copy(day = day, period = null) }
    }

    /** Switches to the week, month or year around what's shown; null goes back to the day view. */
    fun showView(type: PeriodType?) {
        val current = query.value
        val anchor = current.period?.let { minOf(it.end, LocalDate.now()) } ?: current.day
        if (type == null) selectDay(anchor) else showPeriod(type, anchor)
    }

    fun showPeriod(type: PeriodType, day: LocalDate) =
        update { it.copy(period = Period.containing(type, day, WeekFields.of(Locale.getDefault()).firstDayOfWeek)) }

    fun previous() = update { q -> q.period?.let { q.copy(period = it.previous()) } ?: q.copy(day = q.day.minusDays(1)) }

    fun next() = update { q ->
        val period = q.period
        when {
            period != null -> period.next().takeIf { it.start <= LocalDate.now() }?.let { q.copy(period = it) } ?: q
            q.day < LocalDate.now() -> q.copy(day = q.day.plusDays(1))
            else -> q
        }
    }

    /** Shows one bar's value in the header; tapping it again goes back to the whole day or period. */
    fun selectBar(index: Int?) {
        selected.value = if (index == selected.value) null else index
    }

    /** Any change of what's shown clears the tapped bar, which belonged to the old view. */
    private fun update(change: (Query) -> Query) {
        val before = query.value
        val after = change(before)
        if (after != before) {
            selected.value = null
            query.value = after
        }
    }

    companion object {
        const val ARG_PACKAGE = "packageName"
        const val ARG_DAY = "day"
        const val ARG_VIEW = "view"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as WellmuteApp).container
                AppDetailViewModel(createSavedStateHandle(), container.history, container.timers)
            }
        }
    }
}
