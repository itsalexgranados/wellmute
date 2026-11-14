package com.wellmute.ui.bedtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.bedtime.AfterBedtime
import com.wellmute.bedtime.BedtimeMode
import com.wellmute.bedtime.BedtimeSettings
import com.wellmute.ui.common.AccessCard
import com.wellmute.ui.common.NavRow
import com.wellmute.ui.common.ScheduleEditor
import com.wellmute.ui.common.formatMinute
import com.wellmute.ui.common.SectionHeader
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.SwitchRow
import com.wellmute.ui.common.displaySummary
import com.wellmute.ui.common.dndDisplayItems
import com.wellmute.ui.common.dndPeopleItems
import com.wellmute.ui.common.dndSoundsItems
import com.wellmute.ui.common.openSettings
import com.wellmute.ui.common.peopleSummary
import com.wellmute.ui.common.scheduleSummary
import com.wellmute.ui.common.soundsSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Bedtime's groups of settings, each on its own page. */
enum class BedtimeSection { SCHEDULE, PEOPLE, SOUNDS, DISPLAY, APPS, BLOCKED, SCREEN }

/** Bedtime at a glance: on or off, then one row per group showing what's chosen there. */
@Composable
fun BedtimeScreen(onBack: () -> Unit, onOpen: (BedtimeSection) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as WellmuteApp).container
    val bedtime = container.bedtime
    val settings by bedtime.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun change(update: (BedtimeSettings) -> BedtimeSettings) = change(scope, bedtime, update)

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        // Pick up anything changed in Android's own Modes screen.
        scope.launch {
            bedtime.syncFromSystem()
            bedtime.refreshActive()
        }
        refresh++
        onPauseOrDispose { }
    }
    val hasAccess = remember(refresh) { bedtime.hasAccess() }
    // Updated the moment it's switched anywhere (here, Quick Settings, the schedule), without polling.
    val isActive by bedtime.active.collectAsStateWithLifecycle()

    SubpageScaffold(title = R.string.bedtime_title, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            if (!hasAccess) {
                item {
                    AccessCard(R.string.bedtime_needs_access, R.string.access_grant) {
                        context.openSettings(container.permissions.policyAccessSettings())
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (isActive) R.string.bedtime_on else R.string.bedtime_off), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.bedtime_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isActive) {
                            OutlinedButton(onClick = { scope.launch { bedtime.turnOff() } }, enabled = hasAccess) {
                                Text(stringResource(R.string.bedtime_turn_off))
                            }
                        } else {
                            Button(onClick = { scope.launch { bedtime.turnOn() } }, enabled = hasAccess) {
                                Text(stringResource(R.string.bedtime_turn_on))
                            }
                        }
                    }
                }
            }

            item {
                NavRow(stringResource(R.string.focus_schedule), bedtimeScheduleSummary(settings)) { onOpen(BedtimeSection.SCHEDULE) }
            }

            item { SectionHeader(R.string.bedtime_effects) }
            item {
                SwitchRow(R.string.bedtime_dnd, R.string.bedtime_dnd_summary, settings.doNotDisturb) { on ->
                    change(scope, bedtime) { it.copy(doNotDisturb = on) }
                }
            }
            // Who can reach you and what still sounds only matter while Do Not Disturb is on.
            if (settings.doNotDisturb) {
                item {
                    NavRow(stringResource(R.string.bedtime_who_can_reach_you), peopleSummary(settings.policy)) { onOpen(BedtimeSection.PEOPLE) }
                }
                item {
                    NavRow(stringResource(R.string.bedtime_sounds), soundsSummary(settings.policy)) { onOpen(BedtimeSection.SOUNDS) }
                }
                item {
                    NavRow(stringResource(R.string.display_title), displaySummary(settings.policy)) { onOpen(BedtimeSection.DISPLAY) }
                }
            }
            item { NavRow(stringResource(R.string.bedtime_apps), silencedSummary(settings.appRule, settings.silencedApps.size)) { onOpen(BedtimeSection.APPS) } }
            item { NavRow(stringResource(R.string.bedtime_blocked), blockedSummary(settings.blockedApps.size)) { onOpen(BedtimeSection.BLOCKED) } }
            item { NavRow(stringResource(R.string.bedtime_screen), screenSummary(settings)) { onOpen(BedtimeSection.SCREEN) } }

            // What takes over when Bedtime ends before its scheduled end: nothing, or Do Not Disturb.
            item { SectionHeader(R.string.bedtime_after_title) }
            item {
                Text(
                    stringResource(R.string.bedtime_after_help),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AfterOption(
                    title = stringResource(R.string.bedtime_after_nothing),
                    summary = stringResource(R.string.bedtime_after_nothing_summary),
                    selected = settings.afterEarlyEnd == AfterBedtime.NOTHING,
                ) { change { it.copy(afterEarlyEnd = AfterBedtime.NOTHING) } }
            }
            item {
                AfterOption(
                    title = stringResource(R.string.bedtime_after_dnd),
                    summary = stringResource(R.string.bedtime_after_dnd_summary, formatMinute(settings.schedule.endMinute)),
                    selected = settings.afterEarlyEnd == AfterBedtime.DO_NOT_DISTURB,
                ) { change { it.copy(afterEarlyEnd = AfterBedtime.DO_NOT_DISTURB) } }
            }

            item {
                Text(
                    stringResource(R.string.bedtime_sync_note),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One group of Bedtime's settings. */
@Composable
fun BedtimeSectionScreen(section: BedtimeSection, onBack: () -> Unit) {
    val bedtime = (LocalContext.current.applicationContext as WellmuteApp).container.bedtime
    val settings by bedtime.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun change(update: (BedtimeSettings) -> BedtimeSettings) = change(scope, bedtime, update)

    val title = when (section) {
        BedtimeSection.SCHEDULE -> R.string.focus_schedule
        BedtimeSection.PEOPLE -> R.string.bedtime_who_can_reach_you
        BedtimeSection.SOUNDS -> R.string.bedtime_sounds
        BedtimeSection.DISPLAY -> R.string.display_title
        BedtimeSection.SCREEN -> R.string.bedtime_screen
        BedtimeSection.APPS -> R.string.bedtime_apps
        BedtimeSection.BLOCKED -> R.string.bedtime_blocked
    }
    if (section == BedtimeSection.BLOCKED) {
        BlockedAppsPage(settings, onBack) { update -> change(update) }
        return
    }
    if (section == BedtimeSection.APPS) {
        SilencedAppsPage(settings, onBack) { update -> change(update) }
        return
    }
    SubpageScaffold(title = title, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            when (section) {
                BedtimeSection.SCHEDULE -> {
                    item { ScheduleEditor(settings.schedule) { schedule -> change { it.copy(schedule = schedule) } } }
                    if (settings.schedule.enabled) {
                        item {
                            SwitchRow(R.string.bedtime_only_while_charging, R.string.bedtime_only_while_charging_summary, settings.onlyWhileCharging) { on ->
                                change { it.copy(onlyWhileCharging = on) }
                            }
                        }
                        item {
                            SwitchRow(R.string.bedtime_end_with_alarm, R.string.bedtime_end_with_alarm_summary, settings.endWithAlarm) { on ->
                                change { it.copy(endWithAlarm = on) }
                            }
                        }
                    }
                }
                BedtimeSection.PEOPLE -> dndPeopleItems(settings.policy) { policy -> change { it.copy(policy = policy) } }
                BedtimeSection.SOUNDS -> dndSoundsItems(settings.policy) { policy -> change { it.copy(policy = policy) } }
                BedtimeSection.DISPLAY -> dndDisplayItems(settings.policy) { policy -> change { it.copy(policy = policy) } }
                BedtimeSection.APPS, BedtimeSection.BLOCKED -> Unit
                BedtimeSection.SCREEN -> {
                    item { SwitchRow(R.string.bedtime_grayscale, R.string.bedtime_grayscale_summary, settings.grayscale) { on -> change { it.copy(grayscale = on) } } }
                    item { SwitchRow(R.string.bedtime_dim_wallpaper, R.string.bedtime_dim_wallpaper_summary, settings.dimWallpaper) { on -> change { it.copy(dimWallpaper = on) } } }
                    item { SwitchRow(R.string.bedtime_dark_theme, R.string.bedtime_dark_theme_summary, settings.darkTheme) { on -> change { it.copy(darkTheme = on) } } }
                    item { SwitchRow(R.string.bedtime_aod_off, R.string.bedtime_aod_off_summary, settings.alwaysOnDisplayOff) { on -> change { it.copy(alwaysOnDisplayOff = on) } } }
                }
            }
        }
    }
}

private fun change(scope: CoroutineScope, bedtime: BedtimeMode, update: (BedtimeSettings) -> BedtimeSettings) {
    scope.launch { bedtime.update(update) }
}

/** The schedule, plus "only while charging" when it applies. */
@Composable
private fun bedtimeScheduleSummary(settings: BedtimeSettings): String {
    val base = scheduleSummary(settings.schedule)
    return if (settings.schedule.enabled && settings.onlyWhileCharging) {
        "$base ${stringResource(R.string.summary_while_charging)}"
    } else {
        base
    }
}

/** "Grayscale, Dark theme", or "No changes". */
@Composable
private fun screenSummary(settings: BedtimeSettings): String {
    val effects = buildList {
        if (settings.grayscale) add(stringResource(R.string.bedtime_grayscale))
        if (settings.dimWallpaper) add(stringResource(R.string.bedtime_dim_wallpaper))
        if (settings.darkTheme) add(stringResource(R.string.bedtime_dark_theme))
        if (settings.alwaysOnDisplayOff) add(stringResource(R.string.bedtime_aod_off))
    }
    return effects.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.summary_no_screen_changes)
}

/** One choice for "After it's done", as a radio row. */
@Composable
private fun AfterOption(title: String, summary: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
