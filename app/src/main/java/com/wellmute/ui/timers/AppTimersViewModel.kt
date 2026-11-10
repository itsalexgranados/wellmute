package com.wellmute.ui.timers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppChoice
import com.wellmute.apps.AppInfoRepository
import com.wellmute.data.AppTimer
import com.wellmute.data.HistoryRepository
import com.wellmute.data.TimerDao
import com.wellmute.permissions.PermissionChecker
import com.wellmute.timers.ProtectedApps
import com.wellmute.timers.TimerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Every app timer in one place, and a list of apps to add one to. The timers are the same ones
 * set from the dashboard and an app's usage screen: two ways of doing the same thing.
 */
class AppTimersViewModel(
    private val timers: TimerDao,
    private val history: HistoryRepository,
    private val appInfo: AppInfoRepository,
    private val protectedApps: ProtectedApps,
    private val permissions: PermissionChecker,
    private val settings: TimerSettings,
) : ViewModel() {

    data class TimedApp(val timer: AppTimer, val label: String, val usedTodayMillis: Long)

    data class State(
        /** Apps with a timer, by name. */
        val timed: List<TimedApp>,
        /** Apps a timer can be added to: launchable, not protected, without one yet. */
        val addable: List<AppChoice>,
        val hasAccessibility: Boolean,
        /** The 5-minute warning bubble, for every timer at once. */
        val warnBeforeEnd: Boolean,
    )

    private val today = MutableStateFlow(LocalDate.now())
    private val apps = MutableStateFlow<List<AppChoice>>(emptyList())
    private val hasAccessibility = MutableStateFlow(permissions.hasAccessibility())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val usedToday = today.flatMapLatest { history.observeDay(it) }

    val state: StateFlow<State?> = combine(timers.observeAll(), usedToday, apps, hasAccessibility, settings.warnBeforeEnd) { all, day, choices, accessibility, warn ->
        val used = day.apps.associate { it.packageName to it.foregroundMillis }
        val withTimer = all.map { it.packageName }.toSet()
        State(
            timed = all.map { TimedApp(it, appInfo.labelFor(it.packageName), used[it.packageName] ?: 0) }
                .sortedBy { it.label.lowercase() },
            addable = choices.filterNot { it.packageName in withTimer },
            hasAccessibility = accessibility,
            warnBeforeEnd = warn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            apps.value = withContext(Dispatchers.IO) { appInfo.choosableApps(protectedApps::isProtected) }
        }
    }

    /** Coming back to the screen: access may have changed in Settings, or the day may have turned. */
    fun refresh() {
        hasAccessibility.value = permissions.hasAccessibility()
        today.value = LocalDate.now()
    }

    fun save(packageName: String, dailyLimitMillis: Long) {
        // Saving from the add screen goes straight back, which clears this view model: finish anyway.
        viewModelScope.launch { withContext(NonCancellable) { timers.save(AppTimer(packageName, dailyLimitMillis)) } }
    }

    fun delete(packageName: String) {
        viewModelScope.launch { withContext(NonCancellable) { timers.delete(packageName) } }
    }

    fun setWarnBeforeEnd(on: Boolean) {
        viewModelScope.launch { settings.setWarnBeforeEnd(on) }
    }

    fun accessibilitySettings() = permissions.accessibilitySettings()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as WellmuteApp).container
                AppTimersViewModel(container.timers, container.history, container.appInfo, container.protectedApps, container.permissions, container.timerSettings)
            }
        }
    }
}
