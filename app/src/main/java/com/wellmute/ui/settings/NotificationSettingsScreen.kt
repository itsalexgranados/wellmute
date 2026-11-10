package com.wellmute.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.SwitchRow

/** Wellmute's own notifications, each on its own switch. They need Android's permission to post. */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    val notifications = container.notifications
    val prefs by notifications.prefs.collectAsStateWithLifecycle()
    // Re-read when coming back, in case notifications were allowed or blocked in Android's settings.
    var canPost by remember { mutableStateOf(notifications.canPost()) }
    LifecycleResumeEffect(Unit) {
        canPost = notifications.canPost()
        onPauseOrDispose { }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = it }
    // Turning one on asks for the permission if it's missing, so the switch does something.
    fun change(on: Boolean, set: (Boolean) -> Unit) {
        set(on)
        if (on && !canPost) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SubpageScaffold(title = R.string.settings_notifications, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.settings_notifications_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (!canPost) {
                item {
                    AccessCard(R.string.settings_notifications_permission, R.string.access_grant) {
                        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            item {
                SwitchRow(R.string.settings_notif_bedtime, R.string.settings_notif_bedtime_summary, prefs.bedtimeOn) {
                    change(it, notifications::setBedtimeOn)
                }
            }
            item {
                SwitchRow(R.string.settings_notif_focus, R.string.settings_notif_focus_summary, prefs.focusOn) {
                    change(it, notifications::setFocusOn)
                }
            }
            item {
                SwitchRow(R.string.settings_notif_weekly, R.string.settings_notif_weekly_summary, prefs.weeklySummary) {
                    change(it, notifications::setWeeklySummary)
                }
            }
        }
    }
}
