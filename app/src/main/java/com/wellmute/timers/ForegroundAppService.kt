package com.wellmute.timers

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.wellmute.WellmuteApp
import com.wellmute.reminders.ReminderBubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells Wellmute which app is in front, so paused apps can be covered by the pause screen and
 * usage reminders know what is open. It only listens for "a new window appeared" events and reads
 * which app it belongs to; it can't read screen content (see res/xml/accessibility_service.xml).
 */
class ForegroundAppService : AccessibilityService() {

    private val container by lazy { (applicationContext as WellmuteApp).container }
    private val enforcer by lazy { container.pauseEnforcer }
    private val reminders by lazy { container.usageReminders }
    private var bubble: ReminderBubble? = null

    /** Whether a window's class is an activity, cached because the same few repeat all day. */
    private val isActivity = HashMap<String, Boolean>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var checking: Job? = null

    /** Screen off and unlock: usage reminders only count while the phone is in use. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopChecking()
                    bubble?.hide()
                    reminders.onScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> reminders.onUnlocked()
                Intent.ACTION_SCREEN_ON -> startChecking()
            }
        }
    }

    override fun onServiceConnected() {
        enforcer.onPause = ::showPauseScreen
        bubble = ReminderBubble(this).also {
            reminders.showBubble = it::show
            container.breakEndingWarning.showBubble = it::show
            container.blockedUseWarning.showBubble = it::show
            enforcer.showBubble = it::show
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        // Wellmute may start while an app is already open: find out which, without waiting for the
        // next switch. Only this first look goes back hours (to when that app was opened).
        startChecking(firstLookbackMillis = STARTUP_LOOKBACK_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        // Keyboards, dialogs and the notification shade also open windows; only activities change the app in front.
        if (!isActivity(packageName, className)) return
        onForeground(packageName)
    }

    override fun onInterrupt() = Unit

    private fun onForeground(packageName: String) {
        enforcer.onForeground(packageName)
        reminders.onForeground(packageName)
    }

    /**
     * While the screen is on, checks once a minute, from Android's usage events, which app is in
     * front. The window events above can miss one (an app that stays on a single screen, or opened
     * in a way that doesn't announce a new one); without this, a timer could run past its limit
     * until the next app switch.
     */
    private fun startChecking(firstLookbackMillis: Long = CHECK_LOOKBACK_MILLIS) {
        checking?.cancel()
        checking = scope.launch {
            var lookback = firstLookbackMillis
            while (true) {
                withContext(Dispatchers.IO) { container.usageRepository.foregroundPackage(lookback) }?.let(::onForeground)
                // After that, only the last couple of minutes: a few events to read, not hours of them.
                lookback = CHECK_LOOKBACK_MILLIS
                delay(CHECK_EVERY_MILLIS)
            }
        }
    }

    private fun stopChecking() {
        checking?.cancel()
        checking = null
    }

    override fun onDestroy() {
        enforcer.onPause = null
        reminders.showBubble = null
        container.breakEndingWarning.showBubble = null
        container.blockedUseWarning.showBubble = null
        enforcer.showBubble = null
        bubble?.hide()
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    /**
     * Covers the paused app; leaving the pause screen goes home. (Pressing Home first raced with
     * the pause screen and sometimes hid it.) Coming back to the app through recents brings it to
     * the front again, which pauses it again.
     */
    private fun showPauseScreen(packageName: String, reason: PauseReason) {
        startActivity(PauseActivity.intent(this, packageName, reason))
    }

    private fun isActivity(packageName: String, className: String): Boolean = isActivity.getOrPut("$packageName/$className") {
        try {
            packageManager.getActivityInfo(ComponentName(packageName, className), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private companion object {
        const val CHECK_EVERY_MILLIS = 60_000L

        /** A little more than [CHECK_EVERY_MILLIS], so no switch falls between two checks. */
        const val CHECK_LOOKBACK_MILLIS = 90_000L

        /** When Wellmute starts, far enough back to find when the app in front was opened. */
        const val STARTUP_LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L
    }
}
