package com.victorious.tether.service

import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.util.Transport

internal enum class ProxyUpstream(val transport: Transport?) {
    SYSTEM(null), WIFI(Transport.WIFI), VPN(Transport.VPN);

    fun accepts(active: Transport) = transport == null || transport == active

    companion object {
        fun forSession(config: Config, active: Transport): ProxyUpstream = when {
            config.proxyRuns && config.requireProxyVpn -> VPN
            config.mode == Mode.WIFI_DIRECT && active == Transport.WIFI -> WIFI
            else -> SYSTEM
        }
    }
}
