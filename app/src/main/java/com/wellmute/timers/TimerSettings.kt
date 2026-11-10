package com.wellmute.timers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.timersStore: DataStore<Preferences> by preferencesDataStore(name = "timers")

/** Settings for every app timer at once, set on the App timers screen. */
class TimerSettings(context: Context, scope: CoroutineScope) {

    private val store = context.timersStore

    /** A bubble over the app 5 minutes before its time runs out. On unless turned off. */
    val warnBeforeEnd: StateFlow<Boolean> = store.data
        .map { it[WARN_BEFORE_END] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setWarnBeforeEnd(on: Boolean) {
        store.edit { it[WARN_BEFORE_END] = on }
    }

    private companion object {
        val WARN_BEFORE_END = booleanPreferencesKey("warn_before_end")
    }
}
