package com.wellmute.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.dashboard.DayOverview
import com.wellmute.ui.navigation.Routes

private data class Tool(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector,
    val route: String,
    /** An Android Settings page to open instead of a Wellmute screen. */
    val settingsAction: String? = null,
)

private val disconnectTools = listOf(
    Tool(R.string.dashboard_title, R.string.dashboard_summary, Icons.Outlined.DonutLarge, Routes.DASHBOARD),
    Tool(R.string.timers_title, R.string.timers_summary, Icons.Outlined.HourglassEmpty, Routes.TIMERS),
    Tool(R.string.reminders_title, R.string.reminders_summary, Icons.Outlined.NotificationsActive, Routes.REMINDERS),
)

private val interruptionTools = listOf(
    Tool(R.string.focus_title, R.string.focus_summary, Icons.Outlined.TrackChanges, Routes.FOCUS),
    // Do Not Disturb and its schedules are Android's Modes (docs/decisions/0007): one tap away.
    Tool(R.string.home_modes, R.string.home_modes_summary, Icons.Outlined.Tune, route = "", settingsAction = "android.settings.ZEN_MODE_SETTINGS"),
    Tool(R.string.bedtime_title, R.string.bedtime_summary, Icons.Outlined.Bedtime, Routes.BEDTIME),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit, viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timers by container.timers.observeAll().collectAsStateWithLifecycle(emptyList())
    val focusSettings by container.focus.settings.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    val pausingOff = remember(resumes) { !container.permissions.hasAccessibility() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        resumes++
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // Timers or Focus apps are set, but nothing can pause them (e.g. after a force-stop).
            if (pausingOff && (timers.isNotEmpty() || focusSettings.apps.isNotEmpty())) {
                item {
                    AccessCard(R.string.home_pausing_off, R.string.timer_open_accessibility) {
                        context.openSettings(container.permissions.accessibilitySettings())
                    }
                }
            }
            state?.let { current ->
                if (!current.hasUsageAccess) {
                    item {
                        AccessCard(R.string.access_usage_message, R.string.access_grant) {
                            context.openSettings(container.permissions.usageAccessSettings(), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    }
                }
                item {
                    DayOverview(
                        summary = current.today,
                        appInfo = container.appInfo,
                        hasNotificationAccess = current.hasNotificationAccess,
                        centerLabel = R.string.home_today,
                    )
                }
            }
            item { SectionHeader(R.string.home_section_disconnect) }
            items(disconnectTools) { ToolRow(it, onNavigate) }
            item { SectionHeader(R.string.home_section_interruptions) }
            items(interruptionTools) { ToolRow(it, onNavigate) }
        }
    }
}

@Composable
private fun ToolRow(tool: Tool, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(tool.title)) },
        supportingContent = { Text(stringResource(tool.summary)) },
        leadingContent = { Icon(tool.icon, contentDescription = null) },
        // Shows that it leaves Wellmute for Android's settings.
        trailingContent = tool.settingsAction?.let {
            { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.opens_android_settings)) }
        },
        modifier = Modifier.clickable {
            val action = tool.settingsAction
            if (action != null) context.openSettings(Intent(action)) else onNavigate(tool.route)
        },
    )
}
