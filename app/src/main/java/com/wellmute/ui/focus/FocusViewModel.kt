package com.wellmute.ui.focus

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppChoice
import com.wellmute.apps.AppInfoRepository
import com.wellmute.focus.FocusRepository
import com.wellmute.schedule.WeeklySchedule
import com.wellmute.focus.FocusSettings
import com.wellmute.permissions.PermissionChecker
import com.wellmute.timers.ProtectedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class FocusViewModel(
    private val focus: FocusRepository,
    private val packageManager: PackageManager,
    private val appInfo: AppInfoRepository,
    private val protectedApps: ProtectedApps,
    private val permissions: PermissionChecker,
) : ViewModel() {

    data class State(
        val settings: FocusSettings,
        val isActive: Boolean,
        val isOnBreak: Boolean,
        /** Apps that can be marked as distracting: launchable, and not protected. */
        val apps: List<AppChoice>,
        val hasAccessibility: Boolean,
    )

    private val apps = MutableStateFlow<List<AppChoice>>(emptyList())
    private val hasAccessibility = MutableStateFlow(permissions.hasAccessibility())

    /** Focus can turn on or off by itself (schedule, end of a break), so re-check regularly. */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MILLIS)
        }
    }

    val state: StateFlow<State?> = combine(focus.settings, clock, apps, hasAccessibility) { settings, now, apps, accessibility ->
        State(
            settings = settings,
            isActive = settings.isActive(now, ZoneId.systemDefault()),
            isOnBreak = settings.isOnBreak(now) && settings.isScheduledOrOn(now, ZoneId.systemDefault()),
            // Already by name; sortedByDescending is stable, so distracting ones come first, still by name.
            apps = apps.sortedByDescending { it.packageName in settings.apps },
            hasAccessibility = accessibility,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            apps.value = withContext(Dispatchers.IO) { appInfo.choosableApps(protectedApps::isProtected) }
        }
    }

    fun refresh() {
        hasAccessibility.value = permissions.hasAccessibility()
    }

    fun turnOn() = launch { focus.turnOn() }

    fun turnOff() = launch { focus.turnOff() }

    fun takeBreak(minutes: Int) = launch { focus.takeBreak(minutes) }

    fun endBreak() = launch { focus.endBreak() }

    fun setApp(packageName: String, distracting: Boolean) = launch { focus.setApp(packageName, distracting) }

    fun setSchedule(schedule: WeeklySchedule) = launch { focus.setSchedule(schedule) }

    fun setWarnBeforeBreakEnds(on: Boolean) = launch { focus.setWarnBeforeBreakEnds(on) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        private const val TICK_MILLIS = 15_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as WellmuteApp
                val container = app.container
                FocusViewModel(container.focus, app.packageManager, container.appInfo, container.protectedApps, container.permissions)
            }
        }
    }
}
