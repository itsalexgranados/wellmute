package com.wellmute.apps

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

private val Context.categoriesStore: DataStore<Preferences> by preferencesDataStore(name = "categories")

/**
 * Which [AppCategory] each app belongs to, decided in this order: the one you chose, Wellmute's
 * built-in list of common apps, the category the app declares itself, and otherwise Other.
 * All on the device; nothing is looked up online.
 */
class AppCategories(context: Context, scope: CoroutineScope) {

    private val packageManager = context.packageManager
    private val store = context.categoriesStore
    private val declared = ConcurrentHashMap<String, AppCategory>()

    /** The categories you chose, by package. */
    val chosen: StateFlow<Map<String, AppCategory>> = store.data
        .map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val pkg = key.name.removePrefix(KEY_PREFIX).takeIf { key.name.startsWith(KEY_PREFIX) } ?: return@mapNotNull null
                AppCategory.entries.find { it.name == value }?.let { pkg to it }
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The category to use for [packageName], given [chosen] (pass the latest value to react to changes). */
    fun categoryOf(packageName: String, chosen: Map<String, AppCategory> = this.chosen.value): AppCategory =
        chosen[packageName] ?: automaticCategory(packageName)

    /** What Wellmute would pick without your choice. */
    fun automaticCategory(packageName: String): AppCategory = KNOWN_CATEGORIES[packageName] ?: declared.getOrPut(packageName) {
        try {
            AppCategory.fromSystem(packageManager.getApplicationInfo(packageName, 0).category) ?: AppCategory.OTHER
        } catch (_: PackageManager.NameNotFoundException) {
            AppCategory.OTHER
        }
    }

    /** Sets [packageName]'s category; null goes back to the automatic one. */
    suspend fun choose(packageName: String, category: AppCategory?) {
        store.edit { prefs ->
            val key = stringPreferencesKey(KEY_PREFIX + packageName)
            if (category == null) prefs.remove(key) else prefs[key] = category.name
        }
    }

    private companion object {
        const val KEY_PREFIX = "app:"
    }
}
