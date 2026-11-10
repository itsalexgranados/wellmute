package com.wellmute.focus

import android.content.Context
import android.os.PowerManager
import com.wellmute.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Shows a bubble (the one usage reminders use) a minute before a Focus break ends, when Focus
 * mode will come back on and pause the distracting apps again. Only while the screen is on.
 */
class BreakEndingWarning(private val context: Context, focus: FocusRepository, scope: CoroutineScope) {

    /** Set by the accessibility service while it runs, which is what draws the bubble. */
    @Volatile
    var showBubble: ((text: String) -> Unit)? = null

    private data class Break(val until: Long, val warn: Boolean, val resumes: Boolean)

    init {
        scope.launch {
            focus.settings
                .map { Break(it.breakUntil, it.warnBeforeBreakEnds, it.isScheduledOrOn(Instant.ofEpochMilli(it.breakUntil), ZoneId.systemDefault())) }
                .distinctUntilChanged()
                .collectLatest { current ->
                    if (!current.warn || !current.resumes) return@collectLatest
                    val warnAt = current.until - WARN_BEFORE_MILLIS
                    val wait = warnAt - System.currentTimeMillis()
                    // Too late for a warning a minute ahead (a break shorter than that, or one already ending).
                    if (wait < 0) return@collectLatest
                    delay(wait)
                    if (context.getSystemService(PowerManager::class.java).isInteractive) {
                        val text = context.getString(R.string.focus_break_ending_bubble)
                        withContext(Dispatchers.Main) { showBubble?.invoke(text) }
                    }
                }
        }
    }

    private companion object {
        const val WARN_BEFORE_MILLIS = 60_000L
    }
}
