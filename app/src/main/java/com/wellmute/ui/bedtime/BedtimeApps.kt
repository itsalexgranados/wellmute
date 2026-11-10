package com.wellmute.ui.bedtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wellmute.data.AppRule
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.apps.AppChoice
import com.wellmute.bedtime.BedtimeSettings
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.common.SwitchRow
import com.wellmute.ui.common.appSearchItem
import com.wellmute.ui.common.matchesSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apps to silence during Bedtime: their notifications are removed as they arrive, even those
 * Android would let through (for example apps that can interrupt modes, like a calendar).
 */
@Composable
internal fun SilencedAppsPage(settings: BedtimeSettings, onBack: () -> Unit, change: ((BedtimeSettings) -> BedtimeSettings) -> Unit) {
    // Only an allow list: choosing which few apps may reach you beats blocking 200 one by one.
    // Off by default, so turning Bedtime on never hides everything by surprise.
    val allowOnly = settings.appRule == AppRule.ALLOW
    AppChecklistPage(
        title = R.string.bedtime_apps,
        checked = settings.silencedApps,
        showList = allowOnly,
        onToggle = { packageName, on -> change { it.copy(silencedApps = if (on) it.silencedApps + packageName else it.silencedApps - packageName) } },
        onBack = onBack,
    ) {
        item {
            SwitchRow(R.string.bedtime_allow_only, R.string.bedtime_allow_only_summary, allowOnly) { on ->
                // A list kept from the old "block these apps" would mean the opposite now: start empty.
                change { it.copy(appRule = if (on) AppRule.ALLOW else AppRule.BLOCK, silencedApps = if (on && !allowOnly) emptySet() else it.silencedApps) }
            }
        }
        helpText(R.string.bedtime_apps_allow_help)
        if (allowOnly) {
            // What happens to the hidden ones: here, next to the list it applies to.
            item {
                SwitchRow(R.string.bedtime_deliver, R.string.bedtime_deliver_summary, settings.deliverAtEnd) { on ->
                    change { it.copy(deliverAtEnd = on) }
                }
            }
        }
    }
}

/** "All apps follow Do Not Disturb", or "Only 3 apps allowed". */
@Composable
internal fun silencedSummary(rule: AppRule, count: Int): String = when (rule) {
    AppRule.ALLOW -> pluralStringResource(R.plurals.bedtime_apps_allowed, count, count)
    AppRule.BLOCK -> stringResource(R.string.bedtime_apps_all)
}

/**
 * Apps blocked during Bedtime: opening one shows a pause screen, like Focus mode, with "Use it for
 * 5 minutes". Their notifications follow "Allowed notifications", not this list.
 */
@Composable
internal fun BlockedAppsPage(settings: BedtimeSettings, onBack: () -> Unit, change: ((BedtimeSettings) -> BedtimeSettings) -> Unit) {
    AppChecklistPage(
        title = R.string.bedtime_blocked,
        checked = settings.blockedApps,
        showList = true,
        onToggle = { packageName, on -> change { it.copy(blockedApps = if (on) it.blockedApps + packageName else it.blockedApps - packageName) } },
        onBack = onBack,
    ) {
        helpText(R.string.bedtime_blocked_help)
        item {
            SwitchRow(R.string.bedtime_use_warning, R.string.bedtime_use_warning_summary, settings.warnBeforeUseEnds) { on ->
                change { it.copy(warnBeforeUseEnds = on) }
            }
        }
    }
}

/** "None", or "3 apps blocked". */
@Composable
internal fun blockedSummary(count: Int): String =
    if (count == 0) stringResource(R.string.bedtime_blocked_none) else pluralStringResource(R.plurals.bedtime_blocked_count, count, count)

/** [header] rows, then (with [showList]) a searchable list of apps with the [checked] ones first. */
@Composable
private fun AppChecklistPage(
    @StringRes title: Int,
    checked: Set<String>,
    showList: Boolean,
    onToggle: (packageName: String, on: Boolean) -> Unit,
    onBack: () -> Unit,
    header: LazyListScope.() -> Unit,
) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val apps by produceState(emptyList<AppChoice>()) {
        value = withContext(Dispatchers.IO) { container.appInfo.choosableApps(container.protectedApps::isProtected) }
    }

    SubpageScaffold(title = title, onBack = onBack) { padding ->
        LazyColumn(state = listState, contentPadding = padding) {
            header()
            if (!showList) return@LazyColumn
            appSearchItem(query, { query = it }, listState)
            // Checked ones first, so they're easy to find again.
            val shown = apps.filter { matchesSearch(it.label, query) }.sortedByDescending { it.packageName in checked }
            items(shown, key = { it.packageName }) { (packageName, label) ->
                val isChecked = packageName in checked
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { AppIcon(packageName, container.appInfo) },
                    trailingContent = { Checkbox(checked = isChecked, onCheckedChange = { onToggle(packageName, it) }) },
                    modifier = Modifier.clickable { onToggle(packageName, !isChecked) },
                )
            }
        }
    }
}

private fun LazyListScope.helpText(@StringRes text: Int) = item {
    Text(
        stringResource(text),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
