package com.victorious.tether

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.Status
import rikka.shizuku.Shizuku
import com.victorious.tether.data.TetherState
import com.victorious.tether.service.TetherService
import com.victorious.tether.ui.MainScreen
import com.victorious.tether.ui.VictoriousTheme
import com.victorious.tether.util.UpstreamMonitor

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
        val monitor = remember { UpstreamMonitor(this) }
        DisposableEffect(monitor) { monitor.start(); onDispose { monitor.stop() } }
        val upstream by monitor.state.collectAsState()
        val meshIntent = remember { packageManager.getLaunchIntentForPackage(MESH_PACKAGE) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val needed = requiredPermissions(config)
            // Notification permission is optional for function; Wi-Fi permission is not.
            val wifiOk = needed.filter { it != Manifest.permission.POST_NOTIFICATIONS }.all { result[it] == true || granted(it) }
            if (wifiOk) startChecked(config)
        }

        var hasWifi by remember { mutableStateOf(granted(wifiPermission())) }
        val toolsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasWifi = it }

        MainScreen(
            hasWifiPermission = hasWifi,
            onNeedWifiPermission = { toolsPermission.launch(wifiPermission()) },
            config = config,
            status = status,
            stats = stats,
            upstream = upstream,
            onConfig = { config = it; store.save(it) },
            onToggle = {
                if (status is Status.Idle || status is Status.Failed) {
                    val missing = requiredPermissions(config).filterNot { granted(it) }
                    if (missing.isEmpty()) startChecked(config) else permissionLauncher.launch(missing.toTypedArray())
                } else if (status != Status.Stopping) {
                    TetherService.stop(this)
                }
            },
            onOpenMesh = meshIntent?.let { intent ->
                {
                    runCatching { startActivity(intent) }.onFailure {
                        Toast.makeText(this, "Could not open Victorious Mesh", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenSystemHotspot = {
                runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            },
        )
    }

    /** Starts the service, first making sure Shizuku is usable when that is the chosen privilege source. */
    private fun startChecked(config: Config) {
        if (config.mode != Mode.STEALTH_ROOT || config.privilege != Privilege.SHIZUKU) {
            TetherService.start(this); return
        }
        val fail = { msg: String -> TetherState.set(Status.Failed(msg)) }
        if (!Shizuku.pingBinder()) {
            fail("Shizuku is not running. Install it, start it (Wireless debugging or root), then try again."); return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            TetherService.start(this); return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            fail("Shizuku access was denied. Allow Victorious Tether inside the Shizuku app."); return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) TetherService.start(this@MainActivity)
                else fail("Shizuku permission was not granted")
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(SHIZUKU_REQUEST)
    }

    private companion object {
        const val SHIZUKU_REQUEST = 4242
        const val MESH_PACKAGE = "com.victorious.mesh"
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun wifiPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun requiredPermissions(c: Config): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (c.mode == Mode.PROXY_LOCAL || c.mode == Mode.WIFI_DIRECT) {
            add(wifiPermission())
        }
    }
}
