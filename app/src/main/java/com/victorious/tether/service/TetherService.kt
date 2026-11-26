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
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.victorious.tether.MainActivity
import com.victorious.tether.R
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.HotspotInfo
import com.victorious.tether.data.IpCandidate
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.Stats
import com.victorious.tether.data.StatsSnapshot
import com.victorious.tether.data.Status
import com.victorious.tether.data.TetherState
import com.victorious.tether.hotspot.HotspotBackend
import com.victorious.tether.hotspot.HotspotException
import com.victorious.tether.hotspot.LocalOnlyBackend
import com.victorious.tether.hotspot.RootBackend
import com.victorious.tether.hotspot.ShizukuBackend
import com.victorious.tether.hotspot.WifiDirectBackend
import com.victorious.tether.proxy.HttpsProxyDns
import com.victorious.tether.proxy.ProxyCredentials
import com.victorious.tether.proxy.ProxyServer
import com.victorious.tether.util.Net
import com.victorious.tether.util.toUpstreamTransport
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
        var wakeLock: PowerManager.WakeLock? = null
        var failure: String? = null
        try {
            val initial = connectivity.activeNetwork
            val transport = initial?.let(connectivity::getNetworkCapabilities).toUpstreamTransport()
            val upstream = ProxyUpstream.forSession(config, transport)
            check(upstream.accepts(transport)) { "Connect a VPN that includes VictorTether, then start again." }
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VictoriousTether:session").apply { acquire() }
            shareUntilStopped(config, upstream, stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            if (!destroyed) TetherState.set(Status.Stopping)
            withContext(NonCancellable) {
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

    private suspend fun shareUntilStopped(config: Config, upstream: ProxyUpstream, stats: Stats) {
        val job = currentCoroutineContext().job
        var deadline = Long.MAX_VALUE
        for (attempt in 0..MAX_DIRECT_RESTARTS) {
            if (attempt > 0) {
                if (upstream == ProxyUpstream.WIFI) {
                    while (boundUpstream(upstream) == null && timeLeft(deadline) > 0) {
                        showReconnecting("Waiting for Wi-Fi to return before reconnecting the Wi-Fi Direct group.")
                        delay(minOf(1_000L, timeLeft(deadline)))
                    }
                }
                showReconnecting("Reconnecting the Wi-Fi Direct group ($attempt of $MAX_DIRECT_RESTARTS)")
                delay(minOf(1_000L shl (attempt - 1), timeLeft(deadline)))
                if (timeLeft(deadline) == 0L) return
            }
            val backend = hotspotBackend(config, job)
            try {
                val hotspot = backend?.start(config)
                if (attempt == 0 && config.autoStopMinutes > 0) {
                    deadline = SystemClock.elapsedRealtime() + config.autoStopMinutes * 60_000L
                }
                val groupLost = serveConnection(config, hotspot, backend as? WifiDirectBackend, upstream, stats, deadline)
                if (!groupLost) return
            } catch (e: HotspotException) {
                if (attempt == 0 || attempt == MAX_DIRECT_RESTARTS) throw e
            } finally {
                withContext(NonCancellable) { runCatching { withTimeout(10_000) { backend?.stop() } } }
            }
        }
        throw HotspotException("Wi-Fi Direct keeps disconnecting. Android could not keep the group active.")
    }

    /** Returns true when Android has dropped a Wi-Fi Direct group that we own. */
    private suspend fun serveConnection(
        config: Config,
        hotspot: HotspotInfo?,
        direct: WifiDirectBackend?,
        upstream: ProxyUpstream,
        stats: Stats,
        deadline: Long,
    ): Boolean {
        var proxy: ActiveProxy? = null
        var boundNetwork: Network? = null
        var reportedRunning = false
        var missingGroupReports = 0
        var nextGroupCheck = SystemClock.elapsedRealtime() + GROUP_CHECK_INTERVAL
        val groupAddress = hotspot?.proxyAddress

        try {
            while (timeLeft(deadline) > 0) {
                if (direct != null && hotspot != null && SystemClock.elapsedRealtime() >= nextGroupCheck) {
                    when (direct.groupIsActive(hotspot.ssid)) {
                        false -> missingGroupReports++
                        true -> missingGroupReports = 0
                        null -> Unit  // A missing callback is not confirmation of a lost group.
                    }
                    if (missingGroupReports >= 2) {
                        showReconnecting("Android disconnected the Wi-Fi Direct group.")
                        return true
                    }
                    nextGroupCheck = SystemClock.elapsedRealtime() + GROUP_CHECK_INTERVAL
                }

                val network = boundUpstream(upstream)
                if (upstream != ProxyUpstream.SYSTEM && network == null) {
                    withContext(Dispatchers.IO) { proxy?.close() }
                    proxy = null
                    boundNetwork = null
                    reportedRunning = false
                    showReconnecting("Waiting for ${upstream.transport?.label}. Client traffic is paused.")
                } else {
                    if (!reportedRunning || network != boundNetwork) {
                        withContext(Dispatchers.IO) { proxy?.close() }
                        proxy = if (config.proxyRuns) createProxy(config, stats, network, groupAddress) else null
                        withContext(Dispatchers.IO) { proxy?.server?.start() }
                        val addresses = if (groupAddress != null) {
                            listOf(IpCandidate("Wi-Fi Direct", groupAddress.hostAddress.orEmpty(), false))
                        } else withContext(Dispatchers.IO) {
                            Net.candidateAddresses()
                        }
                        TetherState.set(Status.Running(config, hotspot, addresses, System.currentTimeMillis()))
                        boundNetwork = network
                        reportedRunning = true
                    }
                    val counters = stats.snapshot()
                    TetherState.publish(counters)
                    val route = upstream.transport?.let { "${it.label} · " }.orEmpty()
                    updateNotification("$route↑ ${Net.humanBytes(counters.bytesUp)}  ↓ ${Net.humanBytes(counters.bytesDown)} · ${counters.activeConnections} conn")
                }
                delay(minOf(1_000L, timeLeft(deadline)))
            }
            return false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { proxy?.close() }
        }
    }

    private fun createProxy(config: Config, stats: Stats, network: Network?, listenAddress: InetAddress?): ActiveProxy {
        val sockets = network?.socketFactory ?: SocketFactory.getDefault()
        val systemDns = if (network == null) Dns.SYSTEM else object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).asList()
        }
        val dns = config.proxyDnsEndpoint()?.let { HttpsProxyDns(it, sockets, systemDns) }
        val credentials = if (config.authRequired) ProxyCredentials(config.proxyUser, config.proxyPass) else null
        return ActiveProxy(ProxyServer(
            config.port, credentials, stats, dns = dns ?: systemDns,
            openUpstreamSocket = sockets::createSocket, listenAddress = listenAddress,
        ), dns)
    }

    private class ActiveProxy(val server: ProxyServer, private val dns: HttpsProxyDns?) {
        fun close() {
            try { server.stop() } finally { dns?.close() }
        }
    }

    private fun boundUpstream(upstream: ProxyUpstream): Network? {
        if (upstream == ProxyUpstream.SYSTEM) return null
        val network = connectivity.activeNetwork ?: return null
        val transport = connectivity.getNetworkCapabilities(network).toUpstreamTransport()
        return network.takeIf { upstream.accepts(transport) }
    }

    private fun showReconnecting(message: String) {
        val next = Status.Reconnecting(message)
        if (TetherState.status.value != next) {
            TetherState.set(next)
            updateNotification(message)
        }
    }

    private fun timeLeft(deadline: Long) = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

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
        private const val GROUP_CHECK_INTERVAL = 3_000L
        private const val MAX_DIRECT_RESTARTS = 3

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TetherService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, TetherService::class.java).setAction(ACTION_STOP))
        }
    }
}
