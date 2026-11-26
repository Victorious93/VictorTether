package com.victorious.tether.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.victorious.tether.data.UpstreamPreference
import com.victorious.tether.util.toUpstreamTransport
import java.io.Closeable

/** Holds the explicitly requested transport for the lifetime of one sharing session. */
internal class UpstreamRoute(
    private val connectivity: ConnectivityManager,
    val kind: ProxyUpstream,
    choice: UpstreamPreference,
) : Closeable {
    @Volatile private var requestedNetwork: Network? = null
    private val requestTransport = when {
        kind == ProxyUpstream.WIFI && choice == UpstreamPreference.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
        kind == ProxyUpstream.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        else -> null
    }
    private val callback = requestTransport?.let { transport ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { requestedNetwork = network }
            override fun onLost(network: Network) { if (requestedNetwork == network) requestedNetwork = null }
        }
        connectivity.requestNetwork(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addTransportType(transport).build(),
            callback,
        )
        callback
    }

    fun active(): Network? {
        if (kind == ProxyUpstream.SYSTEM) return null
        val network = (if (callback == null) connectivity.activeNetwork else requestedNetwork) ?: return null
        return network.takeIf { kind.accepts(connectivity.getNetworkCapabilities(it).toUpstreamTransport()) }
    }

    override fun close() { callback?.let(connectivity::unregisterNetworkCallback) }
}
