package com.victorious.tether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.victorious.tether.MainActivity
import com.victorious.tether.R
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.HotspotInfo
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.Stats
import com.victorious.tether.data.Status
import com.victorious.tether.data.TetherState
import com.victorious.tether.hotspot.HotspotBackend
import com.victorious.tether.hotspot.LocalOnlyBackend
import com.victorious.tether.hotspot.RootBackend
import com.victorious.tether.hotspot.ShizukuBackend
import com.victorious.tether.proxy.HttpsProxyDns
import com.victorious.tether.proxy.ProxyCredentials
import com.victorious.tether.proxy.ProxyServer
import com.victorious.tether.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns

/** Owns the hotspot + proxy lifetime. Everything the UI/tile shows comes from [TetherState]. */
class TetherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Job? = null
    private var ticker: Job? = null
    private var backend: HotspotBackend? = null
    private var proxy: ProxyServer? = null
    private var proxyDns: HttpsProxyDns? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stats = Stats()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            else -> if (session?.isActive != true) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val config = ConfigStore(this).load()
        val problems = config.validate()
        if (problems.isNotEmpty()) {
            TetherState.set(Status.Failed(problems.joinToString("; ")))
            stopSelf()
            return
        }
        TetherState.set(Status.Starting)
        startForegroundCompat(buildNotification("Starting…"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VictoriousTether:session").apply { acquire() }

        session = scope.launch {
            try {
                val hotspot = bringUpHotspot(config)
                if (config.proxyRuns) startProxy(config)
                val startedAt = System.currentTimeMillis()
                TetherState.set(Status.Running(config, hotspot, Net.candidateAddresses(), startedAt))
                updateNotification("Running · ${hotspot?.ssid ?: "proxy only"}")
                launchTicker(config)
                if (config.autoStopMinutes > 0) {
                    delay(config.autoStopMinutes * 60_000L)
                    shutdown()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                teardown()
                TetherState.set(Status.Failed(e.message ?: e.javaClass.simpleName))
                stopSelf()
            }
        }
    }

    private suspend fun bringUpHotspot(config: Config): HotspotInfo? {
        val b: HotspotBackend = when (config.mode) {
            Mode.PROXY_LOCAL -> LocalOnlyBackend(this) { shutdown() }
            Mode.STEALTH_ROOT -> if (config.privilege == Privilege.SHIZUKU) ShizukuBackend(this) else RootBackend(this)
            Mode.PROXY_ONLY -> return null
        }
        backend = b
        return b.start(config)
    }

    private fun startProxy(config: Config) {
        val creds = if (config.authRequired) ProxyCredentials(config.proxyUser, config.proxyPass) else null
        val dns = config.proxyDnsEndpoint()?.let(::HttpsProxyDns)
        proxyDns = dns
        val server = ProxyServer(config.port, creds, stats, dns = dns ?: Dns.SYSTEM)
        proxy = server
        server.start()
    }

    private fun launchTicker(config: Config) {
        ticker = scope.launch {
            while (isActive) {
                val s = stats.snapshot()
                TetherState.publish(s)
                updateNotification("↑ ${Net.humanBytes(s.bytesUp)}  ↓ ${Net.humanBytes(s.bytesDown)} · ${s.activeConnections} conn")
                delay(1000)
            }
        }
    }

    private fun shutdown() {
        session?.cancel()
        ticker?.cancel()
        teardown()
        TetherState.set(Status.Idle)
        TetherState.publish(com.victorious.tether.data.StatsSnapshot())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        proxy?.stop(); proxy = null
        proxyDns?.close()
        proxyDns = null
        // stop() on the backends is quick; the service scope may already be cancelled, so block briefly.
        backend?.let { b -> runCatching { runBlocking { b.stop() } } }
        backend = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        if (TetherState.status.value is Status.Running || TetherState.status.value is Status.Starting) {
            TetherState.set(Status.Idle)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Victorious Tether session", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        channel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TetherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Victorious Tether")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(n: Notification) {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        const val ACTION_STOP = "com.victorious.tether.STOP"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TetherService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, TetherService::class.java).setAction(ACTION_STOP))
        }
    }
}
