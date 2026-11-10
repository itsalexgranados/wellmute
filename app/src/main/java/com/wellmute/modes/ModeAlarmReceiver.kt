package com.wellmute.modes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.Condition
import com.wellmute.WellmuteApp
import kotlinx.coroutines.launch

/**
 * System broadcasts for Bedtime: re-plans after a restart, an update, a clock or time zone change,
 * or a new next alarm, and notices when Bedtime is switched off outside Wellmute.
 *
 * Exported because the system sends these. Only protected broadcasts (which only the system can
 * send) switch Bedtime; a "mode switched off" message is checked against Android before acting on
 * it. Bedtime's own alarms go to [BedtimeAlarmReceiver], which isn't exported, so another app can't
 * switch Bedtime off.
 */
class ModeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in HANDLED_ACTIONS } ?: return
        val container = (context.applicationContext as WellmuteApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (action) {
                    // A new next alarm: only plan Bedtime's wake-ups again, never switch it.
                    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> container.bedtime.onNextAlarmChanged()
                    NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED -> {
                        // Switched anywhere (Wellmute, Quick Settings, Android's Modes): update what screens show.
                        container.bedtime.refreshActive()
                        val ruleId = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
                        if (ruleId != null && container.bedtime.isBedtimeRule(ruleId) && isOff(context, ruleId)) {
                            container.bedtime.onEnded()
                        }
                    }
                    // Restart, update, clock or time zone change (protected broadcasts only the
                    // system can send): bring Bedtime back in line with its schedule.
                    else -> container.bedtime.onScheduleEvent()
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Asks Android itself, instead of trusting the broadcast's extras. */
    private fun isOff(context: Context, ruleId: String): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).getAutomaticZenRuleState(ruleId) == Condition.STATE_FALSE
    }.getOrDefault(false)

    private companion object {
        val HANDLED_ACTIONS = setOf(
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
