package com.wellmute.timers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.data.AppTimer
import com.wellmute.ui.common.AppIcon
import com.wellmute.ui.common.formatDuration
import com.wellmute.ui.theme.WellmuteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Covers a paused app: its timer has run out, Focus mode is on, or Bedtime blocks it. Leaving it, by button or
 * Back, goes to the home screen, never back to the paused app.
 */
class PauseActivity : ComponentActivity() {

    private var pausedPackage by mutableStateOf("")
    private var reason by mutableStateOf(PauseReason.TIMER)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            WellmuteTheme {
                when (reason) {
                    PauseReason.TIMER -> TimerPauseScreen(pausedPackage, onClose = ::goHome)
                    PauseReason.FOCUS -> FocusPauseScreen(pausedPackage, onClose = ::goHome, onBreak = ::takeBreak)
                    PauseReason.BEDTIME -> BedtimePauseScreen(pausedPackage, onClose = ::goHome, onUseAWhile = ::useAWhile)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent) {
        pausedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        reason = intent.getStringExtra(EXTRA_REASON)?.let(PauseReason::valueOf) ?: PauseReason.TIMER
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Starts a short Focus break, then opens the app that was paused. */
    private fun takeBreak() {
        val container = (applicationContext as WellmuteApp).container
        val packageName = pausedPackage
        container.appScope.launch {
            container.focus.takeBreak(QUICK_BREAK_MINUTES)
            // Wait until the break is visible to the enforcer, or the app would be paused again on opening.
            container.focus.settings.first { it.isOnBreak(Instant.now()) }
            withContext(Dispatchers.Main) {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                finish()
            }
        }
    }

    /** "Use it for 5 minutes": lets all blocked apps through for a while, then opens this one. */
    private fun useAWhile() {
        val container = (applicationContext as WellmuteApp).container
        val packageName = pausedPackage
        container.appScope.launch {
            container.bedtime.openBlockedFor(QUICK_BREAK_MINUTES)
            // Wait until the enforcer sees it, or the app would be blocked again on opening.
            container.bedtime.blockedOpenUntil.first { it > System.currentTimeMillis() }
            withContext(Dispatchers.Main) {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_REASON = "reason"
        const val QUICK_BREAK_MINUTES = 5

        fun intent(context: Context, packageName: String, reason: PauseReason): Intent =
            Intent(context, PauseActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_REASON, reason.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

@Composable
private fun TimerPauseScreen(packageName: String, onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    var used by remember(packageName) { mutableLongStateOf(0L) }
    var timer by remember(packageName) { mutableStateOf<AppTimer?>(null) }
    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            used = container.usageRepository.usedTodayMillis(packageName)
            timer = container.timers.get(packageName)
        }
    }
    PauseLayout(
        packageName = packageName,
        title = stringResource(R.string.pause_title),
        message = stringResource(
            R.string.pause_message,
            container.appInfo.labelFor(packageName),
            formatDuration(used),
            formatDuration(timer?.dailyLimitMillis ?: used),
        ),
        hint = stringResource(R.string.pause_hint),
        onClose = onClose,
    )
}

@Composable
private fun FocusPauseScreen(packageName: String, onClose: () -> Unit, onBreak: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    PauseLayout(
        packageName = packageName,
        title = stringResource(R.string.focus_pause_title),
        message = stringResource(R.string.focus_pause_message, container.appInfo.labelFor(packageName)),
        hint = stringResource(R.string.focus_pause_hint),
        onClose = onClose,
    ) {
        TextButton(onClick = onBreak) {
            Text(stringResource(R.string.focus_pause_break, PauseActivity.QUICK_BREAK_MINUTES))
        }
    }
}

@Composable
private fun PauseLayout(
    packageName: String,
    title: String,
    message: String,
    hint: String,
    onClose: () -> Unit,
    extraAction: @Composable () -> Unit = {},
) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(packageName, container.appInfo, size = 72.dp)
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.pause_close))
            }
            extraAction()
        }
    }
}

@Composable
private fun BedtimePauseScreen(packageName: String, onClose: () -> Unit, onUseAWhile: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    PauseLayout(
        packageName = packageName,
        title = stringResource(R.string.bedtime_pause_title),
        message = stringResource(R.string.bedtime_pause_message, container.appInfo.labelFor(packageName)),
        hint = stringResource(R.string.bedtime_pause_hint),
        onClose = onClose,
    ) {
        TextButton(onClick = onUseAWhile) {
            Text(pluralStringResource(R.plurals.bedtime_pause_use, PauseActivity.QUICK_BREAK_MINUTES, PauseActivity.QUICK_BREAK_MINUTES))
        }
    }
}
