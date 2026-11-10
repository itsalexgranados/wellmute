package com.wellmute.bedtime

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.service.notification.Condition
import android.service.notification.StatusBarNotification
import android.service.notification.ZenDeviceEffects
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wellmute.MainActivity
import com.wellmute.R
import com.wellmute.data.AppRule
import com.wellmute.modes.DndPolicy
import com.wellmute.modes.BedtimeAlarmReceiver
import com.wellmute.modes.ModeAlarmReceiver
import com.wellmute.modes.Senders
import com.wellmute.notifications.NotificationSnoozer
import com.wellmute.schedule.WeeklySchedule
import com.wellmute.timers.ProtectedApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wellmute.ui.common.formatMinute
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val Context.bedtimeStore: DataStore<Preferences> by preferencesDataStore(name = "bedtime")

/** "After it's done": nothing, or Do Not Disturb until Bedtime's scheduled end. */
enum class AfterBedtime { NOTHING, DO_NOT_DISTURB }

data class BedtimeSettings(
    val schedule: WeeklySchedule = WeeklySchedule(days = DayOfWeek.entries.toSet(), startMinute = 22 * 60, endMinute = 7 * 60),
    /** During the scheduled hours, on only while the phone is charging. */
    val onlyWhileCharging: Boolean = false,
    /** The morning alarm ends Bedtime until the next night. */
    val endWithAlarm: Boolean = false,
    val doNotDisturb: Boolean = true,
    /** Who can reach you and what still sounds, while Do Not Disturb is on. */
    val policy: DndPolicy = DndPolicy(),
    // Screen effects.
    val grayscale: Boolean = true,
    val dimWallpaper: Boolean = true,
    val darkTheme: Boolean = true,
    val alwaysOnDisplayOff: Boolean = true,
    /** Block the chosen apps, or allow only them: the others are removed while Bedtime is on. */
    val appRule: AppRule = AppRule.BLOCK,
    /** The apps [appRule] applies to. Wellmute removes notifications even Android would let through. */
    val silencedApps: Set<String> = emptySet(),
    /** Delay what was hidden until Bedtime ends (Android's snooze), instead of deleting it. */
    val deliverAtEnd: Boolean = true,
    /** What to switch on when Bedtime ends before its scheduled end (unplugged, or the first alarm). */
    val afterEarlyEnd: AfterBedtime = AfterBedtime.NOTHING,
    /** Apps that can't be opened while Bedtime is on (a pause screen covers them, like Focus). */
    val blockedApps: Set<String> = emptySet(),
    /** A bubble 1 minute before "Use it for 5 minutes" runs out. */
    val warnBeforeUseEnds: Boolean = true,
)

/**
 * Bedtime mode is a real Android mode (an [AutomaticZenRule]): it shows up in Android's Modes
 * list and Quick Settings, and Android itself applies its effects (Do Not Disturb, grayscale, dim
 * wallpaper, dark theme, no always-on display), so they hold even if Wellmute isn't running.
 * Everything is managed from Wellmute; changes made in Android's Modes screen are read back by
 * [syncFromSystem]. Its schedule is run by Wellmute, which switches the mode at each start and end.
 */
class BedtimeMode(
    private val context: Context,
    scope: CoroutineScope,
    private val snoozer: NotificationSnoozer,
    private val protectedApps: ProtectedApps,
) {

    /**
     * Whether Wellmute hides [sbn] right now: Bedtime is on and its app is one chosen to be silenced.
     * Android may still let it through (for example an app that can interrupt modes), so Wellmute
     * removes it as it arrives. Its own, ongoing ones (music, navigation) and group summaries stay.
     */
    fun silences(sbn: StatusBarNotification): Boolean {
        if (!isActive() || sbn.packageName == context.packageName) return false
        if (sbn.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return false
        val current = settings.value
        return when (current.appRule) {
            // Only an allow list now: "block" just means Wellmute doesn't filter apps at all.
            AppRule.BLOCK -> false
            AppRule.ALLOW -> sbn.packageName !in current.silencedApps &&
                !protectedApps.isProtected(sbn.packageName) &&
                sbn.notification.category !in ALWAYS_THROUGH
        }
    }

    /**
     * How long to delay a silenced notification: until tonight's scheduled end, or an hour at a time
     * when Bedtime has no end in sight (it's snoozed again if it comes back while Bedtime is still
     * on). Null when the user chose to delete them instead.
     */
    fun delayFor(now: ZonedDateTime = ZonedDateTime.now()): Long? {
        val current = settings.value
        if (!current.deliverAtEnd) return null
        val untilEnd = current.schedule.windowAt(now)?.endInclusive?.toInstant()?.toEpochMilli()?.minus(now.toInstant().toEpochMilli())
        return (untilEnd ?: HOUR_MILLIS).coerceAtLeast(MIN_DELAY_MILLIS)
    }

    /** Whether [ruleId] is Bedtime's mode in Android. */
    fun isBedtimeRule(ruleId: String): Boolean = ruleId == ruleId()

    /** Bedtime ended, here or anywhere else (Quick Settings, the morning alarm): bring back what it delayed. */
    suspend fun onEnded() {
        refreshActive()
        snoozer.releaseAll()
    }

    private val store = context.bedtimeStore
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    val settings: StateFlow<BedtimeSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, BedtimeSettings())

    private val ruleIdState: StateFlow<String?> = store.data.map { it[RULE_ID] }.stateIn(scope, SharingStarted.Eagerly, null)

    fun hasAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    /**
     * Whether Bedtime is on, kept in memory so screens don't ask Android every few seconds. It
     * changes when Wellmute switches the mode and when Android reports a change ([refreshActive]).
     */
    private val activeState = MutableStateFlow(false)
    val active: StateFlow<Boolean> = activeState

    fun isActive(): Boolean = activeState.value

    /**
     * Until when (epoch millis) all blocked apps can be used, after "Use it for 5 minutes" on the
     * pause screen; 0 or in the past when they're blocked.
     */
    val blockedOpenUntil: StateFlow<Long> = store.data
        .map { it[BLOCKED_OPEN_UNTIL] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * The apps Bedtime blocks right now: it's on, they're chosen, and the 5 minutes aren't running.
     * Apps that could lock someone out (Phone, Settings…) are never blocked.
     */
    fun blockedNow(now: Long = System.currentTimeMillis()): Set<String> {
        if (!isActive() || blockedOpenUntil.value > now) return emptySet()
        return settings.value.blockedApps.filterNot(protectedApps::isProtected).toSet()
    }

    /** "Use it for 5 minutes": lets every blocked app through until then, not just the one in front. */
    suspend fun openBlockedFor(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        store.edit { it[BLOCKED_OPEN_UNTIL] = until }
    }

    init {
        scope.launch { refreshActive() }
    }

    /** Re-reads from Android whether Bedtime is on. */
    suspend fun refreshActive() = withContext(Dispatchers.IO) {
        val id = ruleId()
        activeState.value = id != null &&
            runCatching { notificationManager.getAutomaticZenRuleState(id) }.getOrNull() == Condition.STATE_TRUE
    }

    /**
     * Copies anything changed in Android's own Modes screen back into Wellmute's settings, so both
     * show the same. Android keeps the user's edits there over later changes from the app anyway.
     */
    suspend fun syncFromSystem() = withContext(Dispatchers.IO) {
        val rule = ruleId()?.let { runCatching { notificationManager.getAutomaticZenRule(it) }.getOrNull() } ?: return@withContext
        val effects = rule.deviceEffects
        store.edit { prefs ->
            val current = prefs.toSettings()
            current.copy(
                doNotDisturb = rule.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
                policy = current.policy.updatedFrom(rule.zenPolicy),
                grayscale = effects?.shouldDisplayGrayscale() ?: current.grayscale,
                dimWallpaper = effects?.shouldDimWallpaper() ?: current.dimWallpaper,
                darkTheme = effects?.shouldUseNightMode() ?: current.darkTheme,
                alwaysOnDisplayOff = effects?.shouldSuppressAmbientDisplay() ?: current.alwaysOnDisplayOff,
            ).writeTo(prefs)
        }
    }

    suspend fun update(change: (BedtimeSettings) -> BedtimeSettings) = withContext(Dispatchers.IO) {
        val before = store.data.first().toSettings()
        store.edit { prefs -> change(prefs.toSettings()).writeTo(prefs) }
        val after = store.data.first().toSettings()
        apply(after)
        // A changed schedule takes effect right away: on if it says so now, off if it doesn't.
        val schedulingChanged = after.schedule != before.schedule ||
            after.onlyWhileCharging != before.onlyWhileCharging ||
            after.endWithAlarm != before.endWithAlarm
        if (schedulingChanged) syncWithSchedule(after)
        if (after.afterEarlyEnd == AfterBedtime.NOTHING) setAfterBedtimeDnd(false)
    }

    suspend fun turnOn() = setActive(true, Condition.SOURCE_USER_ACTION)

    suspend fun turnOff() = setActive(false, Condition.SOURCE_USER_ACTION)

    /**
     * Called by [BedtimeAlarmReceiver] at each scheduled start and end and when the morning alarm
     * rings ([alarmRang]), and by [ModeAlarmReceiver] after a restart, an update or a clock change. Turning Bedtime off by hand lasts until
     * the next of these events, because the schedule only acts at them.
     */
    suspend fun onScheduleEvent(alarmRang: Boolean = false) {
        var endedEarly = false
        if (alarmRang) {
            val current = store.data.first().toSettings()
            val window = current.schedule.windowAt(ZonedDateTime.now())
            // The alarm ended tonight's Bedtime: keep it off until this window is over.
            if (current.endWithAlarm && window != null) {
                store.edit { it[OFF_UNTIL] = window.endInclusive.toInstant().toEpochMilli() }
                endedEarly = activeState.value
            }
        }
        val settings = store.data.first().toSettings()
        syncWithSchedule(settings)
        if (endedEarly) onEndedEarly(settings)
    }

    /** The charger was plugged in or out. Only matters with "only while charging". */
    suspend fun onChargingChanged() {
        val current = store.data.first().toSettings()
        if (current.schedule.enabled && current.onlyWhileCharging && isInWindow(current)) {
            val wasOn = activeState.value
            val on = shouldBeOn(current)
            setActive(on, Condition.SOURCE_SCHEDULE)
            // Unplugged during the night: Bedtime ended early.
            if (wasOn && !on) onEndedEarly(current)
        }
    }

    /**
     * Bedtime ended before its scheduled end (unplugged, or the first alarm rang). With "After
     * it's done" set to Do Not Disturb, that takes over until the scheduled end.
     */
    private suspend fun onEndedEarly(settings: BedtimeSettings) {
        if (settings.afterEarlyEnd == AfterBedtime.DO_NOT_DISTURB && isInWindow(settings)) setAfterBedtimeDnd(true)
    }

    /**
     * Do Not Disturb after Bedtime is a mode of its own, so it shows in Android's Modes and can be
     * turned off there, follows your usual Do Not Disturb settings (no policy of its own), and
     * Wellmute switches it off at Bedtime's scheduled end without touching Do Not Disturb you
     * turned on yourself.
     */
    private suspend fun setAfterBedtimeDnd(on: Boolean) = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext
        val saved = store.data.first()[AFTER_RULE_ID]?.takeIf { runCatching { notificationManager.getAutomaticZenRule(it) }.getOrNull() != null }
        val id = saved ?: if (on) addAfterBedtimeRule() else null
        id ?: return@withContext
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        runCatching {
            notificationManager.setAutomaticZenRuleState(id, Condition(AFTER_CONDITION, context.getString(R.string.after_bedtime_mode), state, Condition.SOURCE_SCHEDULE))
        }.onFailure { Log.w(TAG, "Couldn't switch Do Not Disturb after Bedtime", it) }
    }

    private suspend fun addAfterBedtimeRule(): String? {
        val rule = AutomaticZenRule.Builder(context.getString(R.string.after_bedtime_mode), AFTER_CONDITION)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .setTriggerDescription(context.getString(R.string.after_bedtime_mode_trigger))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setIconResId(R.drawable.ic_bedtime)
            .build()
        val id = runCatching { notificationManager.addAutomaticZenRule(rule) }
            .onFailure { Log.w(TAG, "Android refused the Do Not Disturb after Bedtime mode", it) }
            .getOrNull() ?: return null
        store.edit { it[AFTER_RULE_ID] = id }
        return id
    }

    /** The user's next alarm changed: re-plan the "turn off with first alarm" wake-up only. */
    fun onNextAlarmChanged() = scheduleAlarms(settings.value)

    private suspend fun syncWithSchedule(settings: BedtimeSettings) {
        if (settings.schedule.enabled) setActive(shouldBeOn(settings), Condition.SOURCE_SCHEDULE)
        // Bedtime's scheduled end is also the end of Do Not Disturb after it.
        if (!isInWindow(settings)) setAfterBedtimeDnd(false)
        scheduleAlarms(settings)
    }

    /** Inside the scheduled window, not ended by the morning alarm, and charging if that's required. */
    private suspend fun shouldBeOn(settings: BedtimeSettings): Boolean {
        val offUntil = store.data.first()[OFF_UNTIL] ?: 0
        return isInWindow(settings) &&
            System.currentTimeMillis() >= offUntil &&
            (!settings.onlyWhileCharging || isCharging())
    }

    /** Plugged into a charger. Not "battery charging": a full battery stops charging but is still plugged in. */
    private fun isCharging(): Boolean =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

    private suspend fun setActive(active: Boolean, source: Int) {
        // Bedtime back on (plugged in again, or by hand): it replaces Do Not Disturb after it.
        if (active) setAfterBedtimeDnd(false)
        // Shown at once; Android's answer follows through refreshActive.
        activeState.value = active
        withContext(Dispatchers.IO) {
            // Only (re)create the mode when it's missing: rewriting it on every switch was slow.
            val existing = ruleId()?.takeIf { runCatching { notificationManager.getAutomaticZenRule(it) }.getOrNull() != null }
            val id = existing ?: apply(settings.value) ?: return@withContext
            val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
            runCatching {
                notificationManager.setAutomaticZenRuleState(id, Condition(CONDITION, context.getString(R.string.bedtime_title), state, source))
            }.onFailure { Log.w(TAG, "Couldn't switch Bedtime mode", it) }
        }
        refreshActive()
        // Switched off (by hand, the schedule, charging or the morning alarm): bring back what it delayed.
        if (!active) snoozer.releaseAll()
    }

    private fun isInWindow(settings: BedtimeSettings) = settings.schedule.windowAt(ZonedDateTime.now()) != null

    /**
     * Android doesn't let apps use its own mode scheduler (it drops the owner), so Wellmute wakes up
     * at the next start or end with an exact alarm (USE_EXACT_ALARM, docs/decisions/0006).
     */
    private fun scheduleAlarms(settings: BedtimeSettings) {
        // The next start or end, a second late so the window's edge has certainly passed.
        val boundary = settings.schedule.nextBoundaryAfter(ZonedDateTime.now())?.toInstant()?.toEpochMilli()?.plus(1_000)
        setAlarm(BedtimeAlarmReceiver.ACTION_SCHEDULE, boundary)

        // The user's next alarm (from any alarm clock app), if it rings inside a bedtime window.
        val morningAlarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
            ?.takeIf { settings.endWithAlarm && settings.schedule.windowAt(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) != null }
        setAlarm(BedtimeAlarmReceiver.ACTION_MORNING_ALARM, morningAlarm)
    }

    /** Replaces the wake-up for [action] with one at [at], or just cancels it when [at] is null. */
    @SuppressLint("MissingPermission") // Holds USE_EXACT_ALARM, which lint doesn't know about; checked below anyway.
    private fun setAlarm(action: String, at: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, BedtimeAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.cancel(intent)
        if (at == null) return
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // Shouldn't happen with USE_EXACT_ALARM; if a system withholds it, be a few minutes late rather than never.
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MILLIS, intent)
        }
    }

    /** Creates or updates the system mode to match [settings]. Returns its id, or null without Do Not Disturb access. */
    private suspend fun apply(settings: BedtimeSettings): String? {
        if (!hasAccess()) return null
        val rule = buildRule(settings)
        val existing = ruleId()?.takeIf { notificationManager.getAutomaticZenRule(it) != null }
        if (existing != null) {
            // Rules made by older versions may not be updatable to the current shape; then replace them.
            val updated = runCatching { notificationManager.updateAutomaticZenRule(existing, rule) }.getOrDefault(false)
            if (updated) return existing
            notificationManager.removeAutomaticZenRule(existing)
        }
        // Android may refuse a mode (it did for the reserved bedtime type); never crash over it.
        val id = runCatching { notificationManager.addAutomaticZenRule(rule) }
            .onFailure { Log.w(TAG, "Android refused the Bedtime mode", it) }
            .getOrNull() ?: return null
        store.edit { it[RULE_ID] = id }
        return id
    }

    private fun buildRule(settings: BedtimeSettings): AutomaticZenRule {
        val effects = ZenDeviceEffects.Builder()
            .setShouldDisplayGrayscale(settings.grayscale)
            .setShouldDimWallpaper(settings.dimWallpaper)
            .setShouldUseNightMode(settings.darkTheme)
            .setShouldSuppressAmbientDisplay(settings.alwaysOnDisplayOff)
            .build()
        val schedule = settings.schedule
        return AutomaticZenRule.Builder(context.getString(R.string.bedtime_mode_name), CONDITION)
            // TYPE_BEDTIME is reserved for the system wellbeing app (Google's, where present); the effects are not.
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            // Shown under the mode's name in Settings → Modes.
            .setTriggerDescription(if (schedule.enabled) "${formatMinute(schedule.startMinute)}–${formatMinute(schedule.endMinute)}" else null)
            .setInterruptionFilter(
                if (settings.doNotDisturb) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            .setZenPolicy(settings.policy.toZenPolicy())
            .setDeviceEffects(effects)
            .setIconResId(R.drawable.ic_bedtime)
            .setManualInvocationAllowed(true)
            .build()
    }


    private fun ruleId(): String? = ruleIdState.value

    private fun Preferences.toSettings(): BedtimeSettings {
        val defaults = BedtimeSettings()
        return BedtimeSettings(
            schedule = WeeklySchedule(
                enabled = this[SCHEDULE_ENABLED] ?: defaults.schedule.enabled,
                days = this[SCHEDULE_DAYS]?.mapNotNull { name -> DayOfWeek.entries.find { it.name == name } }?.toSet() ?: defaults.schedule.days,
                startMinute = this[SCHEDULE_START] ?: defaults.schedule.startMinute,
                endMinute = this[SCHEDULE_END] ?: defaults.schedule.endMinute,
            ),
            onlyWhileCharging = this[ONLY_WHILE_CHARGING] ?: defaults.onlyWhileCharging,
            endWithAlarm = this[END_WITH_ALARM] ?: defaults.endWithAlarm,
            doNotDisturb = this[DND] ?: defaults.doNotDisturb,
            policy = DndPolicy(
                callsFrom = this[CALLS_FROM]?.let { name -> Senders.entries.find { it.name == name } } ?: defaults.policy.callsFrom,
                repeatCallers = this[REPEAT_CALLERS] ?: defaults.policy.repeatCallers,
                messagesFrom = this[MESSAGES_FROM]?.let { name -> Senders.entries.find { it.name == name } } ?: defaults.policy.messagesFrom,
                priorityConversations = this[CONVERSATIONS] ?: defaults.policy.priorityConversations,
                alarms = this[ALARMS] ?: defaults.policy.alarms,
                media = this[MEDIA] ?: defaults.policy.media,
                touchSounds = this[TOUCH_SOUNDS] ?: defaults.policy.touchSounds,
                reminders = this[REMINDERS] ?: defaults.policy.reminders,
                events = this[EVENTS] ?: defaults.policy.events,
                dontTurnOnScreen = this[DONT_TURN_ON_SCREEN] ?: defaults.policy.dontTurnOnScreen,
                dontWake = this[DONT_WAKE] ?: defaults.policy.dontWake,
                hideBadges = this[HIDE_BADGES] ?: defaults.policy.hideBadges,
                hideStatusBarIcons = this[HIDE_STATUS_BAR_ICONS] ?: defaults.policy.hideStatusBarIcons,
                dontPop = this[DONT_POP] ?: defaults.policy.dontPop,
                hideFromShade = this[HIDE_FROM_SHADE] ?: defaults.policy.hideFromShade,
            ),
            grayscale = this[GRAYSCALE] ?: defaults.grayscale,
            dimWallpaper = this[DIM_WALLPAPER] ?: defaults.dimWallpaper,
            darkTheme = this[DARK_THEME] ?: defaults.darkTheme,
            alwaysOnDisplayOff = this[AOD_OFF] ?: defaults.alwaysOnDisplayOff,
            silencedApps = this[SILENCED_APPS] ?: defaults.silencedApps,
            appRule = this[APP_RULE]?.let { name -> AppRule.entries.find { it.name == name } } ?: defaults.appRule,
            deliverAtEnd = this[DELIVER_AT_END] ?: defaults.deliverAtEnd,
            afterEarlyEnd = this[AFTER_EARLY_END]?.let { name -> AfterBedtime.entries.find { it.name == name } } ?: defaults.afterEarlyEnd,
            blockedApps = this[BLOCKED_APPS] ?: defaults.blockedApps,
            warnBeforeUseEnds = this[WARN_BEFORE_USE_ENDS] ?: defaults.warnBeforeUseEnds,
        )
    }

    private fun BedtimeSettings.writeTo(prefs: MutablePreferences) {
        prefs[SCHEDULE_ENABLED] = schedule.enabled
        prefs[SCHEDULE_DAYS] = schedule.days.map { it.name }.toSet()
        prefs[SCHEDULE_START] = schedule.startMinute
        prefs[SCHEDULE_END] = schedule.endMinute
        prefs[ONLY_WHILE_CHARGING] = onlyWhileCharging
        prefs[END_WITH_ALARM] = endWithAlarm
        prefs[DND] = doNotDisturb
        prefs[CALLS_FROM] = policy.callsFrom.name
        prefs[REPEAT_CALLERS] = policy.repeatCallers
        prefs[MESSAGES_FROM] = policy.messagesFrom.name
        prefs[CONVERSATIONS] = policy.priorityConversations
        prefs[ALARMS] = policy.alarms
        prefs[MEDIA] = policy.media
        prefs[TOUCH_SOUNDS] = policy.touchSounds
        prefs[REMINDERS] = policy.reminders
        prefs[EVENTS] = policy.events
        prefs[DONT_TURN_ON_SCREEN] = policy.dontTurnOnScreen
        prefs[DONT_WAKE] = policy.dontWake
        prefs[HIDE_BADGES] = policy.hideBadges
        prefs[HIDE_STATUS_BAR_ICONS] = policy.hideStatusBarIcons
        prefs[DONT_POP] = policy.dontPop
        prefs[HIDE_FROM_SHADE] = policy.hideFromShade
        prefs[GRAYSCALE] = grayscale
        prefs[DIM_WALLPAPER] = dimWallpaper
        prefs[DARK_THEME] = darkTheme
        prefs[AOD_OFF] = alwaysOnDisplayOff
        prefs[SILENCED_APPS] = silencedApps
        prefs[APP_RULE] = appRule.name
        prefs[DELIVER_AT_END] = deliverAtEnd
        prefs[AFTER_EARLY_END] = afterEarlyEnd.name
        prefs[BLOCKED_APPS] = blockedApps
        prefs[WARN_BEFORE_USE_ENDS] = warnBeforeUseEnds
    }

    private companion object {
        const val TAG = "WellmuteBedtime"
        const val INEXACT_WINDOW_MILLIS = 10 * 60_000L
        val CONDITION: Uri = "condition://com.wellmute/bedtime".toUri()

        val RULE_ID = stringPreferencesKey("rule_id")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_DAYS = stringSetPreferencesKey("schedule_days")
        val SCHEDULE_START = intPreferencesKey("schedule_start")
        val SCHEDULE_END = intPreferencesKey("schedule_end")
        val ONLY_WHILE_CHARGING = booleanPreferencesKey("only_while_charging")
        val END_WITH_ALARM = booleanPreferencesKey("end_with_alarm")
        val OFF_UNTIL = longPreferencesKey("off_until")
        val DND = booleanPreferencesKey("dnd")
        val CALLS_FROM = stringPreferencesKey("calls_from")
        val REPEAT_CALLERS = booleanPreferencesKey("repeat_callers")
        val MESSAGES_FROM = stringPreferencesKey("messages_from")
        val CONVERSATIONS = booleanPreferencesKey("priority_conversations")
        val ALARMS = booleanPreferencesKey("alarms")
        val MEDIA = booleanPreferencesKey("media")
        val TOUCH_SOUNDS = booleanPreferencesKey("touch_sounds")
        val REMINDERS = booleanPreferencesKey("reminders")
        val EVENTS = booleanPreferencesKey("events")
        val DONT_TURN_ON_SCREEN = booleanPreferencesKey("dont_turn_on_screen")
        val DONT_WAKE = booleanPreferencesKey("dont_wake")
        val HIDE_BADGES = booleanPreferencesKey("hide_badges")
        val HIDE_STATUS_BAR_ICONS = booleanPreferencesKey("hide_status_bar_icons")
        val DONT_POP = booleanPreferencesKey("dont_pop")
        val HIDE_FROM_SHADE = booleanPreferencesKey("hide_from_shade")
        val GRAYSCALE = booleanPreferencesKey("grayscale")
        val DIM_WALLPAPER = booleanPreferencesKey("dim_wallpaper")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AOD_OFF = booleanPreferencesKey("aod_off")
        val SILENCED_APPS = stringSetPreferencesKey("silenced_apps")
        const val HOUR_MILLIS = 60 * 60_000L
        const val MIN_DELAY_MILLIS = 60_000L
        val APP_RULE = stringPreferencesKey("app_rule")

        /** Allow-only still lets calls and alarms through, as well as protected apps (phone, Settings…). */
        val ALWAYS_THROUGH = setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM)

        val DELIVER_AT_END = booleanPreferencesKey("delay_until_end")
        val AFTER_EARLY_END = stringPreferencesKey("after_early_end")
        val BLOCKED_APPS = stringSetPreferencesKey("blocked_apps")
        val WARN_BEFORE_USE_ENDS = booleanPreferencesKey("warn_before_use_ends")
        /** Until when all blocked apps can be used ("Use it for 5 minutes"). */
        val BLOCKED_OPEN_UNTIL = longPreferencesKey("blocked_open_until")
        val AFTER_RULE_ID = stringPreferencesKey("after_rule_id")
        val AFTER_CONDITION: Uri = "condition://com.wellmute/after_bedtime".toUri()
    }
}
