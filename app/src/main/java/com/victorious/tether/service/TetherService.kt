package com.victorious.tether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.victorious.tether.MainActivity
import com.victorious.tether.R
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.Stats
import com.victorious.tether.data.StatsSnapshot
import com.victorious.tether.data.Status
import com.victorious.tether.data.TetherState
import com.victorious.tether.hotspot.HotspotBackend
import com.victorious.tether.hotspot.LocalOnlyBackend
import com.victorious.tether.hotspot.RootBackend
import com.victorious.tether.hotspot.ShizukuBackend
import com.victorious.tether.hotspot.WifiDirectBackend
import com.victorious.tether.proxy.HttpsProxyDns
import com.victorious.tether.proxy.ProxyCredentials
import com.victorious.tether.proxy.ProxyServer
import com.victorious.tether.util.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import java.net.InetAddress
import javax.net.SocketFactory

/** Owns the hotspot + proxy lifetime. Everything the UI/tile shows comes from [TetherState]. */
class TetherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var session: Job? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            else -> if (session == null) begin()
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
        val job = scope.launch(start = CoroutineStart.LAZY) { runSession(config) }
        session = job
        job.start()
    }

    private fun hotspotBackend(config: Config, job: Job): HotspotBackend? =
        when (config.mode) {
            Mode.PROXY_LOCAL -> LocalOnlyBackend(this) {
                if (session === job) requestStop()
            }
            Mode.STEALTH_ROOT -> if (config.privilege == Privilege.SHIZUKU) ShizukuBackend(this) else RootBackend(this)
            Mode.WIFI_DIRECT -> WifiDirectBackend(this)
            Mode.PROXY_ONLY -> null
        }

    private suspend fun runSession(config: Config) {
        val stats = Stats()
        var backend: HotspotBackend? = null
        var proxy: ProxyServer? = null
        var proxyDns: HttpsProxyDns? = null
        var wakeLock: PowerManager.WakeLock? = null
        var failure: String? = null
        try {
            val vpn = if (config.proxyRuns && config.requireProxyVpn) {
                checkNotNull(activeVpn()) { "Connect a VPN that includes VictorTether, then start again." }
            } else null
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VictoriousTether:session").apply { acquire() }
            backend = hotspotBackend(config, currentCoroutineContext().job)
            val hotspot = backend?.start(config)

            if (config.proxyRuns) {
                checkVpn(vpn)
                val sockets = vpn?.socketFactory ?: SocketFactory.getDefault()
                val systemDns = if (vpn == null) Dns.SYSTEM else object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = vpn.getAllByName(hostname).asList()
                }
                proxyDns = config.proxyDnsEndpoint()?.let { HttpsProxyDns(it, sockets, systemDns) }
                val credentials = if (config.authRequired) ProxyCredentials(config.proxyUser, config.proxyPass) else null
                val server = ProxyServer(
                    config.port, credentials, stats,
                    dns = proxyDns ?: systemDns,
                    openUpstreamSocket = sockets::createSocket,
                )
                proxy = server
                withContext(Dispatchers.IO) { server.start() }
            }

            val addresses = withContext(Dispatchers.IO) { Net.candidateAddresses() }
            checkVpn(vpn)
            TetherState.set(Status.Running(config, hotspot, addresses, System.currentTimeMillis()))
            publishTraffic(config, vpn, stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            if (!destroyed) TetherState.set(Status.Stopping)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    proxy?.stop()
                    proxyDns?.close()
                }
                runCatching { withTimeout(10_000) { backend?.stop() } }
                wakeLock?.takeIf { it.isHeld }?.release()
                if (!destroyed) {
                    session = null
                    TetherState.publish(StatsSnapshot())
                    TetherState.set(failure?.let { Status.Failed(it) } ?: Status.Idle)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun publishTraffic(config: Config, vpn: Network?, stats: Stats) {
        val deadline = if (config.autoStopMinutes > 0) {
            SystemClock.elapsedRealtime() + config.autoStopMinutes * 60_000L
        } else Long.MAX_VALUE
        while (true) {
            val timeLeft = deadline - SystemClock.elapsedRealtime()
            if (timeLeft <= 0) return
            checkVpn(vpn)
            val counters = stats.snapshot()
            TetherState.publish(counters)
            val route = if (vpn != null) "VPN · " else ""
            updateNotification("$route↑ ${Net.humanBytes(counters.bytesUp)}  ↓ ${Net.humanBytes(counters.bytesDown)} · ${counters.activeConnections} conn")
            delay(minOf(1000L, timeLeft))
        }
    }

    private fun activeVpn(): Network? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }

    private fun checkVpn(vpn: Network?) {
        check(vpn == null || activeVpn() == vpn) { "VPN disconnected or changed. Reconnect it, then start VictorTether again." }
    }

    private fun requestStop() {
        val current = session
        if (current == null) {
            stopSelf()
            return
        }
        if (current.isActive) {
            TetherState.set(Status.Stopping)
            current.cancel()
        }
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        if (session != null) {
            TetherState.publish(StatsSnapshot())
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
