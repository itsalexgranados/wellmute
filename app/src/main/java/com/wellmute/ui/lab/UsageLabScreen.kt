package com.wellmute.ui.lab

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wellmute.R
import com.wellmute.ui.common.SubpageScaffold
import java.time.LocalDate
import java.util.Locale

@Composable
fun UsageLabScreen(onBack: () -> Unit, viewModel: UsageLabViewModel = viewModel(factory = UsageLabViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Reload when coming back, e.g. from the usage access settings screen.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SubpageScaffold(title = R.string.lab_title, onBack = onBack) { padding ->
        if (!state.hasAccess) {
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.lab_no_access))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.lab_grant_access))
                }
            }
            return@SubpageScaffold
        }

        LazyColumn(contentPadding = padding) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.day == LocalDate.now(),
                        onClick = viewModel::showToday,
                        label = { Text(stringResource(R.string.lab_today)) },
                    )
                    FilterChip(
                        selected = state.day == LocalDate.now().minusDays(1),
                        onClick = viewModel::showYesterday,
                        label = { Text(stringResource(R.string.lab_yesterday)) },
                    )
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            R.string.lab_total,
                            formatDuration(state.resumedTotalMillis),
                            formatDuration(state.visibleTotalMillis),
                            formatDuration(state.systemTotalMillis),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.lab_legend), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.lab_unlocks, state.unlocks), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.rows, key = { it.packageName }) { row ->
                ListItem(
                    headlineContent = { Text(row.label) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.lab_row,
                                formatDuration(row.resumedMillis),
                                formatDuration(row.visibleMillis),
                                formatDuration(row.systemMillis),
                                formatDifference(row.visibleMillis, row.systemMillis),
                            ) + ", " + pluralStringResource(R.plurals.lab_opens, row.opens, row.opens),
                        )
                    },
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
}

private fun formatDifference(ours: Long, system: Long): String =
    if (system == 0L) "–" else String.format(Locale.ROOT, "%+.1f%%", (ours - system) * 100.0 / system)
