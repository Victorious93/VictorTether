package com.victorious.tether.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.victorious.tether.data.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Transport(val label: String) {
    WIFI("Wi-Fi"), CELLULAR("Mobile data"), ETHERNET("Ethernet"), VPN("VPN"), OTHER("Other"), NONE("No connection")
}

/**
 * What the phone is currently using to reach the internet.
 * [staApConcurrent] is the platform's own answer to "can this phone be a Wi-Fi client and run a
 * hotspot at the same time"; null means the platform would not say.
 */
data class UpstreamInfo(
    val transport: Transport = Transport.NONE,
    val validated: Boolean = false,
    val staApConcurrent: Boolean? = null,
)

enum class Severity { OK, INFO, WARN, BAD }

data class Advice(val severity: Severity, val text: String)

object Upstream {
    /** Pure decision logic, kept apart from Android so it can be unit-tested. */
    fun assess(info: UpstreamInfo, mode: Mode): List<Advice> = buildList {
        when (info.transport) {
            Transport.NONE -> add(Advice(Severity.BAD, "This phone has no internet connection. Clients would have none either."))
            else -> if (!info.validated) {
                add(Advice(Severity.WARN, "${info.transport.label} is connected but Android has not confirmed internet access (login page, or no signal). Clients may get nothing."))
            } else {
                add(Advice(Severity.OK, "Sharing via ${info.transport.label}."))
            }
        }

        if (info.transport == Transport.WIFI && mode == Mode.WIFI_DIRECT) {
            add(Advice(Severity.INFO, "Wi-Fi Direct sharing uses this Wi-Fi connection when your phone supports both links at once."))
        }
        if (info.transport == Transport.WIFI && (mode == Mode.PROXY_LOCAL || mode == Mode.STEALTH_ROOT)) {
            when (info.staApConcurrent) {
                true -> add(Advice(Severity.OK, "This phone reports it can stay on Wi-Fi while running a hotspot."))
                false -> add(Advice(Severity.BAD,
                    "This phone cannot run a hotspot while connected to Wi-Fi. Starting will probably drop your Wi-Fi, which is the connection you want to share. " +
                        "Use Mobile data or Ethernet as the source, or bring a hotspot you already have up and use Proxy only."))
                null -> add(Advice(Severity.WARN, "Unknown whether this phone can keep Wi-Fi on while running a hotspot. If Wi-Fi drops after start, it cannot."))
            }
            if (mode == Mode.STEALTH_ROOT) {
                add(Advice(Severity.INFO, "Some phones refuse system tethering while the source is Wi-Fi. If Stealth fails, try Proxy · no root, which does not depend on that rule."))
            }
        }
        if (info.transport == Transport.VPN) {
            add(Advice(Severity.INFO, "Android reports a VPN for VictorTether. Enable Share VPN with proxy clients to bind the proxy to that connection."))
        }
    }

    /** True when the combination is expected to defeat the purpose of the session. */
    fun blocking(advice: List<Advice>) = advice.any { it.severity == Severity.BAD }
}

/** Keeps [state] current while started. Call [start]/[stop] with the UI's lifecycle. */
class UpstreamMonitor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _state = MutableStateFlow(read())
    val state: StateFlow<UpstreamInfo> get() = _state

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLost(network: Network) = refresh()
    }
    private var registered = false

    fun start() {
        if (registered) return
        runCatching { cm.registerDefaultNetworkCallback(callback); registered = true }
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun refresh() { _state.value = read() }

    private fun read(): UpstreamInfo {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val concurrent = runCatching { wifi.isStaApConcurrencySupported }.getOrNull()
        return UpstreamInfo(
            transport = caps.toUpstreamTransport(),
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            staApConcurrent = concurrent,
        )
    }
}

internal fun NetworkCapabilities?.toUpstreamTransport(): Transport = when {
    this == null -> Transport.NONE
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
    else -> Transport.OTHER
}
