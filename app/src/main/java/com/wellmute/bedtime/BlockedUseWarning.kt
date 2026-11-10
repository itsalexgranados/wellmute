package com.wellmute.bedtime

import android.content.Context
import android.os.PowerManager
import com.wellmute.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a bubble ("1 minute until Bedtime block resumes") a minute before "Use it for 5 minutes" on a Bedtime-blocked app runs out, so
 * the pause screen doesn't come as a surprise. Only while Bedtime is on and the screen is on.
 */
class BlockedUseWarning(
    private val context: Context,
    bedtime: BedtimeMode,
    scope: CoroutineScope,
) {
    /** Set by the accessibility service while it runs, which is what draws the bubble. */
    @Volatile
    var showBubble: ((text: String) -> Unit)? = null

    init {
        scope.launch {
            combine(bedtime.blockedOpenUntil, bedtime.settings, bedtime.active) { openUntil, settings, active ->
                if (active && settings.warnBeforeUseEnds) openUntil else 0L
            }.collectLatest { openUntil ->
                val wait = openUntil - WARN_BEFORE_MILLIS - System.currentTimeMillis()
                if (wait < 0) return@collectLatest
                delay(wait)
                if (context.getSystemService(PowerManager::class.java).isInteractive) {
                    val text = context.getString(R.string.bedtime_use_ending_bubble)
                    withContext(Dispatchers.Main) { showBubble?.invoke(text) }
                }
            }
        }
    }

    private companion object {
        const val WARN_BEFORE_MILLIS = 60_000L
    }
}
