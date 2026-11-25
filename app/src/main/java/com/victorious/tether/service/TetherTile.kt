package com.victorious.tether.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.victorious.tether.MainActivity
import com.victorious.tether.data.Status
import com.victorious.tether.data.TetherState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Quick Settings tile. Starting needs runtime permissions, so a failed start opens the app. */
class TetherTile : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null

    override fun onStartListening() {
        watcher = scope.launch { TetherState.status.collect { render(it) } }
    }

    override fun onStopListening() { watcher?.cancel() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        when (TetherState.status.value) {
            is Status.Running, Status.Starting -> TetherService.stop(this)
            else -> runCatching { TetherService.start(this) }.onFailure { openApp() }
        }
    }

    private fun render(s: Status) {
        val tile = qsTile ?: return
        tile.state = when (s) {
            is Status.Running -> Tile.STATE_ACTIVE
            Status.Starting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Victorious Tether"
        tile.subtitle = when (s) {
            is Status.Running -> "On"
            Status.Starting -> "Starting…"
            is Status.Failed -> "Failed"
            Status.Idle -> "Off"
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // legacy branch is SDK_INT < 34 only
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
