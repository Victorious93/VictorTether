package com.victorious.tether

import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.service.ProxyUpstream
import com.victorious.tether.util.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUpstreamTest {
    @Test fun directSessionStartedOverWifiWaitsForWifiToReturn() {
        val binding = ProxyUpstream.forSession(Config(mode = Mode.WIFI_DIRECT), Transport.WIFI)

        assertEquals(ProxyUpstream.WIFI, binding)
        assertFalse(binding.accepts(Transport.NONE))
        assertFalse(binding.accepts(Transport.CELLULAR))
        assertFalse(binding.accepts(Transport.VPN))
        assertTrue(binding.accepts(Transport.WIFI))
    }

    @Test fun explicitVpnSharingTakesPriorityOverWifi() {
        val config = Config(mode = Mode.WIFI_DIRECT, requireProxyVpn = true)
        val binding = ProxyUpstream.forSession(config, Transport.VPN)

        assertEquals(ProxyUpstream.VPN, binding)
        assertTrue(binding.accepts(Transport.VPN))
        assertFalse(binding.accepts(Transport.WIFI))
        assertFalse(binding.accepts(Transport.CELLULAR))
    }

    @Test fun systemRoutingRemainsAvailableForDirectSessionsStartedOnMobileData() {
        val binding = ProxyUpstream.forSession(Config(mode = Mode.WIFI_DIRECT), Transport.CELLULAR)

        assertEquals(ProxyUpstream.SYSTEM, binding)
    }
}
