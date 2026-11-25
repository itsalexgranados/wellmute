package com.wellmute.modes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wellmute.WellmuteApp
import com.wellmute.bedtime.BedtimeMode
import kotlinx.coroutines.launch

/**
 * Bedtime's own wake-ups (its scheduled starts and ends, the morning alarm, the end of a snooze)
 * and the buttons on its notification. Not exported, so only Wellmute's alarms and notifications
 * reach it; another app can't switch, snooze or end Bedtime by sending these actions.
 */
class BedtimeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val work: suspend BedtimeMode.() -> Unit = when (intent.action) {
            ACTION_SCHEDULE, ACTION_SNOOZE_END -> { { onScheduleEvent(alarmRang = false) } }
            ACTION_MORNING_ALARM -> { { onScheduleEvent(alarmRang = true) } }
            ACTION_SNOOZE -> { { snooze() } }
            ACTION_TURN_ON -> { { turnOn() } }
            else -> return
        }
        val container = (context.applicationContext as WellmuteApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.bedtime.work()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE = "com.wellmute.action.BEDTIME_SCHEDULE"
        const val ACTION_MORNING_ALARM = "com.wellmute.action.BEDTIME_MORNING_ALARM"
        const val ACTION_SNOOZE_END = "com.wellmute.action.BEDTIME_SNOOZE_END"

        /** "Snooze 30 min" on the notification. */
        const val ACTION_SNOOZE = "com.wellmute.action.BEDTIME_SNOOZE"

        /** "Turn on now" on the snoozed notification. */
        const val ACTION_TURN_ON = "com.wellmute.action.BEDTIME_TURN_ON"
    }
}
