package com.wellmute.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wellmute.R
import com.wellmute.WellmuteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Quick Settings tile that turns Focus mode on and off. */
class FocusTileService : TileService() {

    private val container by lazy { (applicationContext as WellmuteApp).container }

    override fun onStartListening() = refresh()

    override fun onClick() {
        container.appScope.launch {
            val turnOn = !container.focus.isActive()
            if (turnOn) container.focus.turnOn() else container.focus.turnOff()
            // Wait until the saved setting is visible, so the tile redraws in its new state.
            withTimeoutOrNull(1_000) { container.focus.settings.first { container.focus.isActive() == turnOn } }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        // Set every time: Android caches the name from when the tile was added, so renames would never show.
        tile.label = getString(R.string.tile_focus)
        val settings = container.focus.settings.value
        val active = container.focus.isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            active -> getString(R.string.tile_on)
            settings.isOnBreak(Instant.now()) -> getString(R.string.tile_on_break)
            else -> getString(R.string.tile_off)
        }
        tile.updateTile()
    }
}

/** Quick Settings tile that turns Bedtime mode on and off. */
class BedtimeTileService : TileService() {

    private val container by lazy { (applicationContext as WellmuteApp).container }

    override fun onStartListening() {
        refresh()
        // The mode may have been switched elsewhere: check with Android, then redraw.
        container.appScope.launch {
            container.bedtime.refreshActive()
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    override fun onClick() {
        container.appScope.launch {
            if (container.bedtime.isActive()) container.bedtime.turnOff() else container.bedtime.turnOn()
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        // Set every time: Android caches the name from when the tile was added, so renames would never show.
        tile.label = getString(R.string.tile_bedtime)
        val active = container.bedtime.isActive()
        tile.state = when {
            !container.bedtime.hasAccess() -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }
}
