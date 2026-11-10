package com.wellmute.notifications

import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.notification.ZenPolicy
import androidx.core.content.edit
import androidx.core.net.toUri
import com.wellmute.MainActivity
import com.wellmute.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2 prototype for SPEC §4 risk 1: can notifications be filtered per app without any sound
 * leaking through? Tries both strategies and logs how long each notification lived before we acted.
 *
 * - [Strategy.BLOCKLIST]: nothing is silenced up front; the listener cancels target apps' notifications.
 * - [Strategy.ALLOWLIST]: a system mode silences everything; the listener reposts target apps'
 *   notifications on a channel that may break through the mode.
 *
 * Prototype only: settings live in plain SharedPreferences and the log only in memory.
 */
class NotificationLab(private val context: Context) {

    enum class Strategy { OFF, BLOCKLIST, ALLOWLIST }

    enum class Action { PASSED, CANCELLED, RELAYED, LEFT_SILENT }

    data class Entry(
        val postedAt: Long,
        val packageName: String,
        val title: String,
        val action: Action,
        /** From the moment the notification was posted until we finished acting on it. */
        val latencyMillis: Long,
    )

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("notification_lab", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nextNotificationId = 1

    private val _strategy = MutableStateFlow(
        prefs.getString(KEY_STRATEGY, null)?.let(Strategy::valueOf) ?: Strategy.OFF,
    )
    val strategy: StateFlow<Strategy> = _strategy

    /** Blocked apps with [Strategy.BLOCKLIST], allowed apps with [Strategy.ALLOWLIST]. */
    private val _targets = MutableStateFlow(prefs.getStringSet(KEY_TARGETS, null)?.toSet() ?: setOf(context.packageName))
    val targets: StateFlow<Set<String>> = _targets

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log

    fun hasListenerAccess(): Boolean =
        notificationManager.isNotificationListenerAccessGranted(ComponentName(context, WellmuteNotificationListener::class.java))

    fun hasPolicyAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun canPostNotifications(): Boolean = notificationManager.areNotificationsEnabled()

    /** Whether Android accepted our request for the relay channel to break through modes. */
    fun relayChannelBypassesModes(): Boolean = notificationManager.getNotificationChannel(RELAY_CHANNEL)?.canBypassDnd() == true

    fun setStrategy(strategy: Strategy) {
        _strategy.value = strategy
        prefs.edit { putString(KEY_STRATEGY, strategy.name) }
        setSilentModeActive(strategy == Strategy.ALLOWLIST)
    }

    fun toggleTarget(packageName: String) {
        _targets.update { if (packageName in it) it - packageName else it + packageName }
        prefs.edit { putStringSet(KEY_TARGETS, _targets.value) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /** Posts a notification with sound from Wellmute itself, after a delay so the screen can be locked first. */
    fun sendTestNotification(delayMillis: Long) {
        ensureChannels()
        scope.launch {
            delay(delayMillis)
            val notification = Notification.Builder(context, TEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.notification_lab_test_title))
                .setContentText(context.getString(R.string.notification_lab_test_text))
                .setAutoCancel(true)
                .build()
            notificationManager.notify(nextNotificationId++, notification)
        }
    }

    /** Called by [WellmuteNotificationListener] for every notification posted on the device. */
    fun onPosted(listener: NotificationListenerService, sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (sbn.packageName == context.packageName && notification.channelId == RELAY_CHANNEL) return
        // Persistent notifications (music, navigation) and group summaries aren't messages.
        if (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return

        val isTarget = sbn.packageName in _targets.value
        val action = when (_strategy.value) {
            Strategy.OFF -> Action.PASSED
            Strategy.BLOCKLIST -> if (isTarget) {
                listener.cancelNotification(sbn.key)
                Action.CANCELLED
            } else {
                Action.PASSED
            }
            Strategy.ALLOWLIST -> if (isTarget) {
                relay(sbn)
                listener.cancelNotification(sbn.key)
                Action.RELAYED
            } else {
                Action.LEFT_SILENT
            }
        }
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val entry = Entry(sbn.postTime, sbn.packageName, title, action, System.currentTimeMillis() - sbn.postTime)
        _log.update { (listOf(entry) + it).take(MAX_LOG_ENTRIES) }
    }

    /** Reposts an allowed app's notification on our relay channel, which may sound during the mode. */
    private fun relay(sbn: StatusBarNotification) {
        ensureChannels()
        val original = sbn.notification
        val relayed = Notification.Builder(context, RELAY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(original.extras.getCharSequence(Notification.EXTRA_TITLE))
            .setContentText(original.extras.getCharSequence(Notification.EXTRA_TEXT))
            .setSubText(sbn.packageName)
            .setContentIntent(original.contentIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(nextNotificationId++, relayed)
    }

    /** Turns the lab's own system mode on or off; it silences everything except priority channels. */
    private fun setSilentModeActive(active: Boolean) {
        if (!hasPolicyAccess()) return
        ensureChannels()
        val existing = prefs.getString(KEY_RULE_ID, null)?.takeIf { notificationManager.getAutomaticZenRule(it) != null }
        if (!active) {
            // Remove the lab's mode entirely, so it doesn't linger in Settings → Modes.
            existing?.let(notificationManager::removeAutomaticZenRule)
            prefs.edit { remove(KEY_RULE_ID) }
            return
        }
        val ruleId = existing ?: createRule()
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        notificationManager.setAutomaticZenRuleState(
            ruleId,
            Condition(RULE_CONDITION, context.getString(R.string.notification_lab_rule_name), state, Condition.SOURCE_USER_ACTION),
        )
    }

    private fun createRule(): String {
        val policy = ZenPolicy.Builder()
            .disallowAllSounds()
            .allowPriorityChannels(true)
            .build()
        val rule = AutomaticZenRule.Builder(context.getString(R.string.notification_lab_rule_name), RULE_CONDITION)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(policy)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .build()
        return notificationManager.addAutomaticZenRule(rule).also { prefs.edit { putString(KEY_RULE_ID, it) } }
    }

    private fun ensureChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, context.getString(R.string.notification_lab_test_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        // Android only honors "bypass modes" for apps with Do Not Disturb access, and a channel's
        // setting can't change once it exists, so the relay channel waits until access is granted.
        if (hasPolicyAccess() && notificationManager.getNotificationChannel(RELAY_CHANNEL) == null) {
            val relay = NotificationChannel(RELAY_CHANNEL, context.getString(R.string.notification_lab_relay_channel), NotificationManager.IMPORTANCE_HIGH)
            relay.setBypassDnd(true)
            notificationManager.createNotificationChannel(relay)
        }
    }

    private companion object {
        const val KEY_STRATEGY = "strategy"
        const val KEY_TARGETS = "targets"
        const val KEY_RULE_ID = "rule_id"
        const val TEST_CHANNEL = "lab_test"
        const val RELAY_CHANNEL = "lab_relay"
        const val MAX_LOG_ENTRIES = 50
        val RULE_CONDITION: Uri = "condition://com.wellmute/notification_lab".toUri()
    }
}
