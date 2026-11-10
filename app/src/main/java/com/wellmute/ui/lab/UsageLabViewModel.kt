package com.wellmute.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppInfoRepository
import com.wellmute.usage.CountingMode
import com.wellmute.usage.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Phase 2 prototype: compares our usage calculation with Android's own numbers. */
class UsageLabViewModel(private val repository: UsageRepository, private val appInfo: AppInfoRepository) : ViewModel() {

    data class Row(
        val packageName: String,
        val label: String,
        val resumedMillis: Long,
        val visibleMillis: Long,
        val systemMillis: Long,
        val opens: Int,
    )

    data class State(
        val hasAccess: Boolean = true,
        val day: LocalDate = LocalDate.now(),
        val resumedTotalMillis: Long = 0,
        val visibleTotalMillis: Long = 0,
        val systemTotalMillis: Long = 0,
        val unlocks: Int = 0,
        val rows: List<Row> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun showToday() = select(LocalDate.now())

    fun showYesterday() = select(LocalDate.now().minusDays(1))

    private fun select(day: LocalDate) {
        _state.update { it.copy(day = day) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val day = _state.value.day
            val newState = withContext(Dispatchers.IO) {
                if (!repository.hasUsageAccess()) return@withContext State(hasAccess = false, day = day)
                val now = System.currentTimeMillis()
                val resumed = repository.summaryFor(day, CountingMode.RESUMED, now)
                val visible = repository.summaryFor(day, CountingMode.VISIBLE, now).apps.associateBy { it.packageName }
                val system = repository.systemVisibleMillis(day, now)
                val rows = (resumed.apps.map { it.packageName } + visible.keys).distinct().map { pkg ->
                    Row(
                        packageName = pkg,
                        label = appInfo.labelFor(pkg),
                        resumedMillis = resumed.apps.find { it.packageName == pkg }?.foregroundMillis ?: 0L,
                        visibleMillis = visible[pkg]?.foregroundMillis ?: 0L,
                        systemMillis = system[pkg] ?: 0L,
                        opens = visible[pkg]?.opens ?: 0,
                    )
                }.sortedByDescending { it.visibleMillis }
                State(
                    hasAccess = true,
                    day = day,
                    resumedTotalMillis = rows.sumOf { it.resumedMillis },
                    visibleTotalMillis = rows.sumOf { it.visibleMillis },
                    systemTotalMillis = rows.sumOf { it.systemMillis },
                    unlocks = resumed.unlocks,
                    rows = rows,
                )
            }
            _state.value = newState
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as WellmuteApp
                UsageLabViewModel(app.container.usageRepository, app.container.appInfo)
            }
        }
    }
}
