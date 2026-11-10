package com.wellmute

import android.content.Context
import com.wellmute.apps.AppInfoRepository
import com.wellmute.bedtime.BedtimeMode
import com.wellmute.bedtime.BlockedUseWarning
import com.wellmute.data.HistoryDatabase
import com.wellmute.data.HistoryRepository
import com.wellmute.apps.AppCategories
import com.wellmute.focus.BreakEndingWarning
import com.wellmute.focus.FocusHistory
import com.wellmute.focus.FocusInsightsRepository
import com.wellmute.focus.FocusRepository
import com.wellmute.notifications.NotificationSnoozer
import com.wellmute.notifications.WellmuteNotifications
import com.wellmute.notifications.NotificationCounter
import com.wellmute.notifications.NotificationLab
import com.wellmute.permissions.PermissionChecker
import com.wellmute.reminders.UsageReminders
import com.wellmute.timers.PauseEnforcer
import com.wellmute.timers.TimerSettings
import com.wellmute.timers.ProtectedApps
import com.wellmute.ui.setup.SetupState
import com.wellmute.usage.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId

/**
 * Manual dependency graph for the whole app (see docs/decisions/0001).
 * Repositories, the Room database and DataStore get created here as the phases add them.
 */
class AppContainer(context: Context) {
    private val database = HistoryDatabase.create(context)

    /** Lives as long as the app process, for state that is always kept up to date. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val usageRepository = UsageRepository(context)
    val appInfo = AppInfoRepository(context)
    val permissions = PermissionChecker(context, usageRepository)
    val categories = AppCategories(context, appScope)
    val history = HistoryRepository(database.historyDao(), usageRepository, categories) { day ->
        // Read when a day is saved, long after [bedtime] exists.
        bedtime.settings.value.schedule.windowsWithin(day, ZoneId.systemDefault())
    }
    val timers = database.timerDao()
    val protectedApps = ProtectedApps(context)
    val focus = FocusRepository(context, appScope, database.historyDao())
    val focusHistory = FocusHistory(database.historyDao(), focus, appScope)
    val focusInsights = FocusInsightsRepository(database.historyDao(), context.packageName)
    val notificationSnoozer = NotificationSnoozer()
    val bedtime = BedtimeMode(context, appScope, notificationSnoozer, protectedApps)
    val notifications = WellmuteNotifications(context, appScope, bedtime, focus)
    val timerSettings = TimerSettings(context, appScope)
    val blockedUseWarning = BlockedUseWarning(context, bedtime, appScope)
    val pauseEnforcer = PauseEnforcer(context, timers, usageRepository, appInfo, protectedApps, focus, timerSettings, database.historyDao(), bedtime)
    val usageReminders = UsageReminders(context, appInfo, protectedApps, usageRepository, appScope)
    val breakEndingWarning = BreakEndingWarning(context, focus, appScope)
    val notificationCounter =NotificationCounter(history, context.packageName)
    val notificationLab = NotificationLab(context)
    val setup = SetupState(context, appScope)
}
