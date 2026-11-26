package com.victorious.tether

import com.victorious.tether.data.Mode
import com.victorious.tether.util.Severity
import com.victorious.tether.util.Transport
import com.victorious.tether.util.Upstream
import com.victorious.tether.util.UpstreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamTest {
    private fun wifi(concurrent: Boolean?, validated: Boolean = true) = UpstreamInfo(Transport.WIFI, validated, concurrent)

    @Test fun wifiWithoutConcurrencyIsBlockingForHotspotModes() {
        for (m in listOf(Mode.PROXY_LOCAL, Mode.STEALTH_ROOT)) {
            assertTrue(m.name, Upstream.blocking(Upstream.assess(wifi(false), m)))
        }
    }

    @Test fun proxyOnlyIgnoresConcurrencyBecauseYouBringTheLink() {
        assertFalse(Upstream.blocking(Upstream.assess(wifi(false), Mode.PROXY_ONLY)))
    }

    @Test fun wifiWithConcurrencyIsFine() {
        val a = Upstream.assess(wifi(true), Mode.PROXY_LOCAL)
        assertFalse(Upstream.blocking(a))
        assertTrue(a.any { it.severity == Severity.OK && "stay on Wi-Fi" in it.text })
    }

    @Test fun unknownConcurrencyWarnsButDoesNotBlock() {
        val a = Upstream.assess(wifi(null), Mode.PROXY_LOCAL)
        assertFalse(Upstream.blocking(a))
        assertTrue(a.any { it.severity == Severity.WARN })
    }

    @Test fun noConnectionBlocks() {
        assertTrue(Upstream.blocking(Upstream.assess(UpstreamInfo(), Mode.PROXY_LOCAL)))
    }

    @Test fun unvalidatedNetworkWarns() {
        val a = Upstream.assess(wifi(true, validated = false), Mode.PROXY_LOCAL)
        assertTrue(a.any { it.severity == Severity.WARN && "not confirmed" in it.text })
    }

    @Test fun cellularNeedsNoConcurrencyAndIsClean() {
        val a = Upstream.assess(UpstreamInfo(Transport.CELLULAR, true, false), Mode.STEALTH_ROOT)
        assertEquals(listOf(Severity.OK), a.map { it.severity })
    }

    @Test fun stealthOverWifiAddsTetherRestrictionNote() {
        assertTrue(Upstream.assess(wifi(true), Mode.STEALTH_ROOT).any { "refuse system tethering" in it.text })
    }
}

class WifiDirectModeTest {
    @Test fun wifiDirectOverWifiNeverBlocksEvenWithoutApConcurrency() {
        val info = UpstreamInfo(Transport.WIFI, true, staApConcurrent = false)
        val a = Upstream.assess(info, Mode.WIFI_DIRECT)
        assertFalse(Upstream.blocking(a))
        assertTrue(a.any { "Wi-Fi Direct normally keeps your Wi-Fi" in it.text })
    }

    @Test fun networkNameGetsDirectPrefixAndFits32Bytes() {
        assertEquals("DIRECT-vt-Home", com.victorious.tether.hotspot.WifiDirect.networkNameFor("Home"))
        assertEquals("DIRECT-ab-Mine", com.victorious.tether.hotspot.WifiDirect.networkNameFor("DIRECT-ab-Mine"))
        assertTrue(com.victorious.tether.hotspot.WifiDirect.networkNameFor("x".repeat(60)).toByteArray().size <= 32)
    }

    @Test fun wifiDirectModeValidatesCredentialsLikeStealth() {
        val c = com.victorious.tether.data.Config(mode = Mode.WIFI_DIRECT, ssid = "n", password = "short")
        assertTrue(c.validate().any { "password" in it })
    }

    @Test fun groupLossIsOnlySuspectedWhileRunningAndNotFormed() {
        val check = com.victorious.tether.hotspot.WifiDirectBackend.Companion::shouldCheckForLoss
        assertTrue(check(true, false))
        assertFalse(check(true, true))   // group still up
        assertFalse(check(true, null))   // broadcast without info
        assertFalse(check(false, false)) // setup or our own teardown
    }
}
