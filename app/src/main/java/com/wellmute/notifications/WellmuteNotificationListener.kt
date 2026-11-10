package com.wellmute.notifications

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import com.wellmute.WellmuteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives every notification posted on the device once the user grants notification access.
 * Hides those of paused apps and of apps silenced during Bedtime (cancelled as they
 * arrive: docs/decisions/0004), and counts the rest: the ones that actually reach you.
 */
class WellmuteNotificationListener : NotificationListenerService() {

    private val container by lazy { (applicationContext as WellmuteApp).container }
    private var scope: CoroutineScope? = null

    override fun onListenerConnected() {
        container.notificationCounter.onConnected(activeNotifications ?: emptyArray())
        container.notificationSnoozer.listener = this
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            // When an app gets paused, clear the notifications it is already showing.
            launch {
                container.pauseEnforcer.pausedPackages.collect { paused ->
                    activeNotifications.orEmpty().filter { it.packageName in paused }.forEach { cancelNotification(it.key) }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        container.notificationSnoozer.listener = null
        scope?.cancel()
        scope = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Only what actually reaches you is counted. A notification Wellmute hides isn't; one it
        // delays until Bedtime ends is counted when it comes back (Android posts it again then).
        // Bedtime's silenced apps: taken away as they arrive, even ones Android lets through, and
        // either delayed until Bedtime ends or, if the user chose so, deleted.
        if (container.bedtime.silences(sbn)) {
            val delayFor = container.bedtime.delayFor()
            if (delayFor != null) container.notificationSnoozer.snooze(sbn.key, delayFor) else cancelNotification(sbn.key)
            return
        }
        if (container.pauseEnforcer.isPaused(sbn.packageName)) {
            cancelNotification(sbn.key)
            return
        }
        if (!hiddenByDoNotDisturb(sbn)) container.notificationCounter.onPosted(sbn)
        container.notificationLab.onPosted(this, sbn)
    }

    /**
     * Do Not Disturb (Android's, or a mode like Bedtime) can keep a notification out of the
     * notification shade altogether. One it only silences still shows there, so it still counts.
     */
    private fun hiddenByDoNotDisturb(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) != true) return false
        return !ranking.matchesInterruptionFilter() &&
            ranking.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        container.notificationCounter.onRemoved(sbn)
    }
}
