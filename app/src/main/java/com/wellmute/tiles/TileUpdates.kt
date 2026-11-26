package com.wellmute.tiles

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import com.wellmute.bedtime.BedtimeMode
import com.wellmute.focus.FocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Tells Android that a tile changed, so it asks the tile to redraw even while the Quick Settings
 * panel is closed. Without this, a Focus or Bedtime schedule starting with the panel closed left
 * the tile drawn as Off until it was tapped, because Android shows the last drawing it kept.
 */
class TileUpdates(
    private val context: Context,
    scope: CoroutineScope,
    focus: FocusRepository,
    bedtime: BedtimeMode,
) {
    init {
        // Focus: checked at every settings change and exactly when a schedule or a break starts or ends.
        scope.launch {
            focus.loaded()
            val shown = MutableStateFlow<Pair<Boolean, Boolean>?>(null)
            launch { shown.collect { if (it != null) request(FocusTileService::class.java) } }
            focus.settings.collectLatest { settings ->
                while (true) {
                    val now = Instant.now()
                    shown.value = settings.isActive(now, ZoneId.systemDefault()) to settings.isOnBreak(now)
                    val next = settings.nextChange(now, ZoneId.systemDefault()) ?: break
                    delay((next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1_000))
                }
            }
        }
        // Bedtime: whenever it's switched, here or by its schedule (the tile itself redraws on a tap).
        scope.launch {
            bedtime.active.collect { request(BedtimeTileService::class.java) }
        }
    }

    private fun request(tile: Class<out TileService>) {
        // Android refuses it for a tile that isn't in the panel; that's fine.
        runCatching { TileService.requestListeningState(context, ComponentName(context, tile)) }
            .onFailure { Log.d(TAG, "Couldn't ask for ${tile.simpleName} to redraw", it) }
    }

    private companion object {
        const val TAG = "WellmuteTiles"
    }
}
