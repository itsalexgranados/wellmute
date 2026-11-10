package com.wellmute.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.data.DaySummary
import com.wellmute.data.HistoryRepository
import com.wellmute.permissions.PermissionChecker
import com.wellmute.widget.TodayWidget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(
    private val history: HistoryRepository,
    private val permissions: PermissionChecker,
    /** Called after today has been recalculated, to refresh the home screen widget. */
    private val onSynced: suspend () -> Unit,
) : ViewModel() {

    data class State(val today: DaySummary, val hasUsageAccess: Boolean, val hasNotificationAccess: Boolean)

    // Re-emitted on every refresh, so the screen rolls over to the new day after midnight.
    private val today = MutableStateFlow(LocalDate.now())
    private val access = MutableStateFlow(permissions.hasUsageAccess() to permissions.hasNotificationAccess())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State?> = today
        .flatMapLatest { day ->
            combine(history.observeDay(day), access) { summary, (usage, notifications) -> State(summary, usage, notifications) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        today.value = LocalDate.now()
        access.value = permissions.hasUsageAccess() to permissions.hasNotificationAccess()
        viewModelScope.launch {
            history.syncDay(today.value)
            onSynced()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as WellmuteApp
                val container = app.container
                HomeViewModel(container.history, container.permissions) { TodayWidget.update(app) }
            }
        }
    }
}
