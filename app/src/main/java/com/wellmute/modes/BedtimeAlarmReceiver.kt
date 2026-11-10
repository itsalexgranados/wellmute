package com.wellmute.modes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wellmute.WellmuteApp
import kotlinx.coroutines.launch

/**
 * Bedtime's own wake-ups: its scheduled starts and ends, and the morning alarm. Not exported, so
 * only Wellmute's alarms reach it; another app can't switch Bedtime off by sending these actions.
 */
class BedtimeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmRang = when (intent.action) {
            ACTION_SCHEDULE -> false
            ACTION_MORNING_ALARM -> true
            else -> return
        }
        val container = (context.applicationContext as WellmuteApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.bedtime.onScheduleEvent(alarmRang = alarmRang)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE = "com.wellmute.action.BEDTIME_SCHEDULE"
        const val ACTION_MORNING_ALARM = "com.wellmute.action.BEDTIME_MORNING_ALARM"
    }
}
