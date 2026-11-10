package com.wellmute.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.wellmute.data.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts the notifications that reach you, per app ([WellmuteNotificationListener] leaves out the
 * ones it hides). Only new notifications count: an app updating one it
 * already shows (a download's progress, a chat getting another message) doesn't add to the count
 * until that notification has been dismissed.
 */
class NotificationCounter(
    private val history: HistoryRepository,
    private val ownPackage: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shownKeys = ConcurrentHashMap.newKeySet<String>()

    /** Notifications already showing when the listener connects were counted before, or predate us. */
    fun onConnected(active: Array<StatusBarNotification>) {
        shownKeys.clear()
        active.forEach { shownKeys += it.key }
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == ownPackage) return
        // Persistent notifications (music, navigation) and group summaries aren't messages.
        if (sbn.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (!shownKeys.add(sbn.key)) return
        // When it reached you: a delayed one counts at the time it came back, not when it was sent.
        scope.launch { history.recordNotification(System.currentTimeMillis(), sbn.packageName) }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        shownKeys -= sbn.key
    }
}
