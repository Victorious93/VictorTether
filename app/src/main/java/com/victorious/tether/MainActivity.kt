package com.victorious.tether

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.Mode
import com.victorious.tether.data.TetherState
import com.victorious.tether.service.TetherService
import com.victorious.tether.ui.MainScreen
import com.victorious.tether.ui.VictoriousTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        setContent {
            VictoriousTheme {
                Root(store)
            }
        }
    }

    @Composable
    private fun Root(store: ConfigStore) {
        var config by remember { mutableStateOf(store.load()) }
        val status by TetherState.status.collectAsState()
        val stats by TetherState.stats.collectAsState()

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val needed = requiredPermissions(config)
            // Notification permission is optional for function; Wi-Fi permission is not.
            val wifiOk = needed.filter { it != Manifest.permission.POST_NOTIFICATIONS }.all { result[it] == true || granted(it) }
            if (wifiOk) TetherService.start(this)
        }

        MainScreen(
            config = config,
            status = status,
            stats = stats,
            onConfig = { config = it; store.save(it) },
            onToggle = {
                if (status is com.victorious.tether.data.Status.Idle || status is com.victorious.tether.data.Status.Failed) {
                    val missing = requiredPermissions(config).filterNot { granted(it) }
                    if (missing.isEmpty()) TetherService.start(this) else permissionLauncher.launch(missing.toTypedArray())
                } else {
                    TetherService.stop(this)
                }
            },
            onOpenSystemHotspot = {
                runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            },
        )
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(c: Config): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (c.mode == Mode.PROXY_LOCAL) {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
