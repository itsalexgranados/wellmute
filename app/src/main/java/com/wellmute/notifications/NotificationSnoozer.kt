package com.wellmute.notifications

import android.service.notification.NotificationListenerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Delays notifications instead of deleting them: Android's own snooze (the same as swiping a
 * notification and choosing a snooze time) hides one and brings it back by itself when the time is
 * up. Bedtime snoozes what it silences until it ends, and brings the rest back early if it ends
 * sooner. Android does the waiting, so they come back even if Wellmute isn't running then.
 */
class NotificationSnoozer {

    /** Set by [WellmuteNotificationListener] while it's connected: only a listener can snooze. */
    @Volatile
    var listener: NotificationListenerService? = null

    /** What Wellmute snoozed, so it brings back only its own and never the ones the user snoozed. */
    private val snoozedKeys = ConcurrentHashMap.newKeySet<String>()

    fun snooze(key: String, durationMillis: Long) {
        val service = listener ?: return
        runCatching { service.snoozeNotification(key, durationMillis) }.onSuccess { snoozedKeys += key }
    }

    /**
     * Brings back now everything Wellmute snoozed. Android has no "unsnooze" for apps, but snoozing
     * a snoozed notification again for a moment makes it come back right away.
     */
    fun releaseAll() {
        val service = listener ?: return
        val stillSnoozed = runCatching { service.snoozedNotifications?.map { it.key }?.toSet() }.getOrNull().orEmpty()
        snoozedKeys.filter { it in stillSnoozed }.forEach { key ->
            runCatching { service.snoozeNotification(key, RELEASE_MILLIS) }
        }
        snoozedKeys.clear()
    }

    private companion object {
        const val RELEASE_MILLIS = 1L
    }
}
