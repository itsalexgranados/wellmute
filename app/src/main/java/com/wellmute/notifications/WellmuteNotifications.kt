package com.wellmute.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.wellmute.MainActivity
import com.wellmute.R
import com.wellmute.bedtime.BedtimeMode
import com.wellmute.bedtime.BedtimeSettings
import com.wellmute.bedtime.BedtimeSnooze
import com.wellmute.bedtime.SNOOZE_MINUTES
import com.wellmute.modes.BedtimeAlarmReceiver
import com.wellmute.focus.FocusRepository
import com.wellmute.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Context.notificationPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "notification_prefs")

/** What Bedtime's notification shows. */
private data class BedtimeState(val active: Boolean, val settings: BedtimeSettings, val snooze: BedtimeSnooze?, val prefs: NotificationPrefs)

/** Which of Wellmute's own notifications to show; each can be turned off in Settings. */
data class NotificationPrefs(val bedtimeOn: Boolean = true, val focusOn: Boolean = true, val weeklySummary: Boolean = true)

/**
 * Wellmute's own notifications: a quiet ongoing one while Bedtime or Focus mode is on, saying
 * until when, and a weekly summary ([WeeklySummaryWorker]). All silent; none needs the internet.
 */
class WellmuteNotifications(
    private val context: Context,
    private val scope: CoroutineScope,
    bedtime: BedtimeMode,
    focus: FocusRepository,
) {
    private val store = context.notificationPrefsStore
    private val manager = context.getSystemService(NotificationManager::class.java)

    val prefs: StateFlow<NotificationPrefs> = store.data
        .map { NotificationPrefs(it[BEDTIME_ON] ?: true, it[FOCUS_ON] ?: true, it[WEEKLY] ?: true) }
        .stateIn(scope, SharingStarted.Eagerly, NotificationPrefs())

    init {
        createChannels()
        // Bedtime: shown while it's on, from whichever switch turned it on (schedule, tile, app, Settings),
        // with "Snooze 30 min"; and while it's snoozed, with "Turn on now".
        scope.launch {
            combine(bedtime.active, bedtime.settings, bedtime.snooze, prefs) { active, settings, snooze, prefs -> BedtimeState(active, settings, snooze, prefs) }
                .collectLatest { (active, settings, snooze, prefs) ->
                    when {
                        !prefs.bedtimeOn -> manager.cancel(BEDTIME_ID)
                        active -> {
                            val end = settings.schedule.windowAt(ZonedDateTime.now())?.endInclusive?.toInstant()
                            post(
                                BEDTIME_ID,
                                BEDTIME_CHANNEL,
                                R.drawable.ic_bedtime_tile,
                                end?.let { context.getString(R.string.notif_bedtime_until, timeOf(it)) } ?: context.getString(R.string.notif_bedtime_on),
                                context.getString(R.string.notif_bedtime_text),
                                Routes.BEDTIME,
                                actions = listOf(bedtimeAction(BedtimeAlarmReceiver.ACTION_SNOOZE, context.getString(R.string.notif_bedtime_snooze, SNOOZE_MINUTES))),
                            )
                        }
                        snooze != null -> post(
                            BEDTIME_ID,
                            BEDTIME_CHANNEL,
                            R.drawable.ic_bedtime_tile,
                            context.getString(R.string.notif_bedtime_snoozed, timeOf(Instant.ofEpochMilli(snooze.until))),
                            context.getString(if (snooze.resumes) R.string.notif_bedtime_snoozed_text else R.string.notif_bedtime_snoozed_night_text),
                            Routes.BEDTIME,
                            actions = listOf(bedtimeAction(BedtimeAlarmReceiver.ACTION_TURN_ON, context.getString(R.string.bedtime_turn_on))),
                        )
                        else -> manager.cancel(BEDTIME_ID)
                    }
                }
        }
        // Focus: re-checked exactly when it may turn on or off by itself (schedule, end of a break).
        scope.launch {
            combine(focus.settings, prefs) { settings, prefs -> settings to prefs }.collectLatest { (settings, prefs) ->
                while (true) {
                    val now = Instant.now()
                    val zone = ZoneId.systemDefault()
                    if (prefs.focusOn && settings.isActive(now, zone)) {
                        // Turned on by hand, it lasts until it's turned off; on a schedule, until the window ends.
                        val end = if (settings.manualOn) null else settings.schedule.windowAt(now.atZone(zone))?.endInclusive?.toInstant()
                        post(
                            FOCUS_ID,
                            FOCUS_CHANNEL,
                            R.drawable.ic_focus,
                            end?.let { context.getString(R.string.notif_focus_until, timeOf(it)) } ?: context.getString(R.string.notif_focus_on),
                            context.getString(R.string.notif_focus_text),
                            Routes.FOCUS,
                        )
                    } else {
                        manager.cancel(FOCUS_ID)
                    }
                    val next = settings.nextChange(now, zone) ?: awaitCancellation()
                    delay((next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1_000))
                }
            }
        }
    }

    fun canPost(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun setBedtimeOn(on: Boolean) = edit(BEDTIME_ON, on)
    fun setFocusOn(on: Boolean) = edit(FOCUS_ON, on)
    fun setWeeklySummary(on: Boolean) = edit(WEEKLY, on)

    private fun edit(key: Preferences.Key<Boolean>, on: Boolean) {
        scope.launch { store.edit { it[key] = on } }
    }

    /** The weekly summary, posted by [WeeklySummaryWorker]. */
    fun postWeeklySummary(title: String, text: String) {
        if (!prefs.value.weeklySummary) return
        post(WEEKLY_ID, WEEKLY_CHANNEL, R.drawable.ic_launcher_foreground, title, text, Routes.DASHBOARD, ongoing = false)
    }

    /** A button on Bedtime's notification, handled by the (not exported) [BedtimeAlarmReceiver]. */
    private fun bedtimeAction(action: String, label: String): Notification.Action {
        val intent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, BedtimeAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, intent).build()
    }

    private fun post(
        id: Int,
        channel: String,
        icon: Int,
        title: String,
        text: String,
        route: String,
        ongoing: Boolean = true,
        actions: List<Notification.Action> = emptyList(),
    ) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(
            context,
            id,
            MainActivity.open(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(!ongoing)
            .setCategory(if (ongoing) Notification.CATEGORY_STATUS else Notification.CATEGORY_RECOMMENDATION)
            .setActions(*actions.toTypedArray())
            .build()
        manager.notify(id, notification)
    }

    private fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(BEDTIME_CHANNEL, context.getString(R.string.notif_channel_bedtime), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(FOCUS_CHANNEL, context.getString(R.string.notif_channel_focus), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(WEEKLY_CHANNEL, context.getString(R.string.notif_channel_weekly), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /** "07:00", in the phone's own time format. */
    private fun timeOf(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

    private companion object {
        val BEDTIME_ON = booleanPreferencesKey("bedtime_on")
        val FOCUS_ON = booleanPreferencesKey("focus_on")
        val WEEKLY = booleanPreferencesKey("weekly_summary")

        const val BEDTIME_CHANNEL = "bedtime_status"
        const val FOCUS_CHANNEL = "focus_status"
        const val WEEKLY_CHANNEL = "weekly_summary"
        const val BEDTIME_ID = 1001
        const val FOCUS_ID = 1002
        const val WEEKLY_ID = 1003
    }
}
