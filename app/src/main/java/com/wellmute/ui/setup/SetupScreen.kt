package com.wellmute.ui.setup

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.permissions.PermissionChecker
import com.wellmute.ui.common.openSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.appStore: DataStore<Preferences> by preferencesDataStore(name = "app")

/** Whether the first-launch setup has been finished. Null until read from disk. */
class SetupState(context: Context, scope: CoroutineScope) {
    private val store = context.appStore

    val done: StateFlow<Boolean?> = store.data.map { it[DONE] ?: false }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun markDone() {
        store.edit { it[DONE] = true }
    }

    private companion object {
        val DONE = booleanPreferencesKey("setup_done")
    }
}

/** Re-reads every permission's status whenever the screen comes back from Settings. */
@Composable
fun rememberPermissionStatus(checker: PermissionChecker): Map<AppPermission, Boolean> {
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return remember(resumes) { AppPermission.entries.associateWith { checker.isGranted(it) } }
}

/** One page of the first-launch setup. */
private sealed interface SetupStep {
    data class Intro(val page: Int) : SetupStep
    data class Ask(val permission: AppPermission, val number: Int, val of: Int) : SetupStep
    data object Done : SetupStep
}

private const val INTRO_PAGES = 3

/** The brand indigo the logo sits on, as on the launcher icon. */
private val LogoBackground = Color(0xFF283593)

private val AppPermission.icon: ImageVector
    get() = when (this) {
        AppPermission.USAGE -> Icons.Outlined.BarChart
        AppPermission.NOTIFICATIONS -> Icons.Outlined.Notifications
        AppPermission.POST_NOTIFICATIONS -> Icons.Outlined.NotificationsActive
        AppPermission.ACCESSIBILITY -> Icons.Outlined.AccessibilityNew
        AppPermission.BATTERY -> Icons.Outlined.BatteryFull
    }

/**
 * First launch: three short pages on what Wellmute does, then one page per permission it still
 * needs (why, and what it's never used for), then a summary. Every permission can be skipped.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    val checker = container.permissions
    val granted = rememberPermissionStatus(checker)

    // Worked out once: only what's missing when the setup starts (a reinstall may already have some).
    val steps = remember {
        val missing = AppPermission.entries.filterNot { checker.isGranted(it) }
        buildList {
            repeat(INTRO_PAGES) { add(SetupStep.Intro(it)) }
            missing.forEachIndexed { i, permission -> add(SetupStep.Ask(permission, i + 1, missing.size)) }
            add(SetupStep.Done)
        }
    }
    val fromFile = remember { checker.installedFromFile() }
    // The restricted-setting hint only on the first page where Android may block it: once
    // "Allow restricted settings" is on, it's on for all of them, so later pages would only repeat it.
    val firstRestricted = remember { steps.indexOfFirst { it is SetupStep.Ask && it.permission.restricted } }
    val permissionCount = steps.count { it is SetupStep.Ask }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // The permission whose Settings page was just opened, to tell what happened on coming back.
    var waitingFor by rememberSaveable { mutableStateOf<AppPermission?>(null) }
    var forward by remember { mutableStateOf(true) }
    // "Skip for now" asks first; this is the permission it would skip.
    var confirmSkip by rememberSaveable { mutableStateOf<AppPermission?>(null) }
    var showRestrictedHelp by rememberSaveable { mutableStateOf(false) }

    fun goTo(target: Int) {
        forward = target > index
        index = target.coerceIn(0, steps.lastIndex)
    }
    fun finish() {
        container.appScope.launch { container.setup.markDone() }
        onDone()
    }

    BackHandler(enabled = index > 0) { goTo(index - 1) }

    val step = steps[index]

    // Back from Settings. Allowed: the page shows a tick and Next, and the person moves on (moving
    // on by itself, a moment later, raced with coming back to the app). Not allowed: installed from
    // a file, Android blocks some of them ("Restricted setting"), and only after showing that message
    // does App info's ⋮ menu offer "Allow restricted settings": so this is the moment to explain those steps.
    val currentStep by rememberUpdatedState(step)
    val currentWaitingFor by rememberUpdatedState(waitingFor)
    LifecycleResumeEffect(Unit) {
        val asked = currentWaitingFor
        val shown = currentStep
        if (asked != null && shown is SetupStep.Ask && shown.permission == asked) {
            waitingFor = null
            if (!checker.isGranted(asked) && asked.restricted && fromFile) showRestrictedHelp = true
        }
        onPauseOrDispose { }
    }

    confirmSkip?.let { permission ->
        AlertDialog(
            onDismissRequest = { confirmSkip = null },
            title = { Text(stringResource(R.string.setup_skip_title)) },
            text = { Text(stringResource(R.string.setup_skip_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkip = null
                    (steps.getOrNull(index) as? SetupStep.Ask)?.takeIf { it.permission == permission }?.let { goTo(index + 1) }
                }) { Text(stringResource(R.string.setup_skip_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = null }) { Text(stringResource(R.string.setup_skip_cancel)) } },
        )
    }
    if (showRestrictedHelp) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showRestrictedHelp = false },
            icon = { Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null) },
            title = { Text(stringResource(R.string.setup_restricted_help_title)) },
            text = { Text(stringResource(R.string.setup_restricted_help_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestrictedHelp = false
                    context.openSettings(checker.appInfoSettings())
                }) { Text(stringResource(R.string.restricted_open_app_info)) }
            },
            dismissButton = { TextButton(onClick = { showRestrictedHelp = false }) { Text(stringResource(R.string.setup_not_now)) } },
        )
    }

    Scaffold { padding ->
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val sign = if (forward) 1 else -1
                (slideInHorizontally(tween(TRANSITION_MILLIS)) { sign * it / 5 } + fadeIn(tween(TRANSITION_MILLIS)))
                    .togetherWith(slideOutHorizontally(tween(TRANSITION_MILLIS)) { -sign * it / 5 } + fadeOut(tween(TRANSITION_MILLIS)))
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "setup",
        ) { shown ->
            val onBack: (() -> Unit)? = if (shown > 0) ({ goTo(shown - 1) }) else null
            when (val s = steps[shown]) {
                is SetupStep.Intro -> IntroPage(
                    page = s.page,
                    permissionCount = permissionCount,
                    onBack = onBack,
                    onNext = { goTo(shown + 1) },
                    onSkip = { goTo(INTRO_PAGES) },
                )
                is SetupStep.Ask -> PermissionPage(
                    step = s,
                    granted = granted[s.permission] == true,
                    restrictedHint = shown == firstRestricted && fromFile,
                    checker = checker,
                    onBack = onBack,
                    onAllow = { waitingFor = s.permission },
                    onNext = { goTo(shown + 1) },
                    // Optional: Wellmute works without it, so there's nothing to warn about.
                    onSkip = { if (s.permission.optional) goTo(shown + 1) else confirmSkip = s.permission },
                    onRestrictedHelp = { showRestrictedHelp = true },
                )
                SetupStep.Done -> DonePage(granted = granted, checker = checker, onBack = onBack, onFinish = ::finish)
            }
        }
    }
}

/**
 * The common shape of every page: a back arrow (except on the first page) beside an optional top
 * line, the content centred in what's left (scrolling on small screens or large fonts), and the
 * buttons at the bottom.
 */
@Composable
private fun SetupPage(
    onBack: (() -> Unit)?,
    top: @Composable BoxScope.() -> Unit = {},
    buttons: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                // Lined up with the content's left edge, as the icon has its own padding.
                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart, content = top)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                content = content,
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, content = buttons)
    }
}

@Composable
private fun PageIcon(icon: ImageVector) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun IntroPage(page: Int, permissionCount: Int, onBack: (() -> Unit)?, onNext: () -> Unit, onSkip: () -> Unit) {
    SetupPage(
        onBack = onBack,
        top = {
            if (page < INTRO_PAGES - 1) {
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterEnd)) { Text(stringResource(R.string.setup_skip_intro)) }
            }
        },
        buttons = {
            PageDots(current = page, count = INTRO_PAGES)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (page == INTRO_PAGES - 1 && permissionCount > 0) R.string.setup_set_up_permissions else R.string.setup_next))
            }
        },
    ) {
        when (page) {
            0 -> {
                Box(Modifier.size(96.dp).clip(CircleShape).background(LogoBackground), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
                }
                Text(
                    stringResource(R.string.setup_welcome),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                PageText(R.string.setup_intro_time_title, R.string.setup_intro_time_text)
            }
            1 -> {
                PageIcon(Icons.Outlined.Bedtime)
                PageText(R.string.setup_intro_rest_title, R.string.setup_intro_rest_text)
            }
            else -> {
                PageIcon(Icons.Outlined.Shield)
                PageText(R.string.setup_intro_private_title, R.string.setup_intro_private_text)
                if (permissionCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.setup_intro_private_next, permissionCount, permissionCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageText(title: Int, text: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == current) 18.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun StepHeader(number: Int, of: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_step, number, of), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { number.toFloat() / of }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
    }
}

@Composable
private fun PermissionPage(
    step: SetupStep.Ask,
    granted: Boolean,
    restrictedHint: Boolean,
    checker: PermissionChecker,
    onBack: (() -> Unit)?,
    onAllow: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onRestrictedHelp: () -> Unit,
) {
    val request = rememberPermissionRequest(checker)
    SetupPage(
        onBack = onBack,
        top = { StepHeader(step.number, step.of) },
        buttons = {
            if (granted) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_next)) }
            } else {
                Button(
                    onClick = {
                        onAllow()
                        request(step.permission)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_allow)) }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_skip)) }
            }
        },
    ) {
        Box {
            PageIcon(step.permission.icon)
            if (granted) {
                // A tick on the icon's corner, with a ring of background so it stands apart from it.
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.setup_granted),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp).size(30.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (step.permission.optional) {
                Text(stringResource(R.string.setup_optional), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(step.permission.title), style = MaterialTheme.typography.headlineMedium)
        }
        Text(stringResource(step.permission.why), style = MaterialTheme.typography.bodyLarge)
        PermissionBullets(step.permission, MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Text(stringResource(step.permission.notUsedFor), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (restrictedHint && !granted) {
            Text(stringResource(R.string.setup_restricted_hint_step), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRestrictedHelp, modifier = Modifier.offset(x = (-12).dp)) { Text(stringResource(R.string.setup_restricted_how)) }
        }
    }
}

@Composable
private fun DonePage(granted: Map<AppPermission, Boolean>, checker: PermissionChecker, onBack: (() -> Unit)?, onFinish: () -> Unit) {
    val request = rememberPermissionRequest(checker)
    // Optional ones don't count: without them, Wellmute still works.
    val allGranted = AppPermission.entries.filterNot { it.optional }.all { granted[it] == true }
    SetupPage(
        onBack = onBack,
        buttons = { Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_open_wellmute)) } },
    ) {
        PageIcon(Icons.Outlined.Check)
        Text(stringResource(if (allGranted) R.string.setup_all_set else R.string.setup_almost_set), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(if (allGranted) R.string.setup_all_set_text else R.string.setup_missing_text), style = MaterialTheme.typography.bodyLarge)
        Column {
            AppPermission.entries.forEachIndexed { i, permission ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(permission.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text(
                        stringResource(permission.title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                    if (granted[permission] == true) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.setup_granted), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = { request(permission) }) { Text(stringResource(R.string.setup_allow)) }
                    }
                }
            }
        }
    }
}

private const val TRANSITION_MILLIS = 250
