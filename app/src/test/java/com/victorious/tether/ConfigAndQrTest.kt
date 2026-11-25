package com.victorious.tether

import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.data.ProxyDnsMode
import com.victorious.tether.util.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAndQrTest {
    @Test fun qrEscapesSpecialCharactersAndFlagsHidden() {
        assertEquals("WIFI:T:WPA;S:a\\;b;P:p\\:w\\\\d;H:true;;", Net.wifiQrPayload("a;b", "p:w\\d", hidden = true))
        assertEquals("WIFI:T:WPA;S:x;P:yyyyyyyy;;", Net.wifiQrPayload("x", "yyyyyyyy", hidden = false))
    }

    @Test fun stealthModeValidatesSsidAndPassword() {
        val ok = Config(mode = Mode.STEALTH_ROOT, ssid = "net", password = "12345678")
        assertTrue(ok.validate().isEmpty())
        assertTrue(ok.copy(password = "short").validate().any { "password" in it })
        assertTrue(ok.copy(ssid = "x".repeat(33)).validate().any { "SSID" in it })
    }

    @Test fun proxyOnlyForcesLoginAndNeedsCredentials() {
        val c = Config(mode = Mode.PROXY_ONLY, proxyPass = "")
        assertTrue(c.authRequired)
        assertTrue(c.validate().any { "username and password" in it })
        assertTrue(c.copy(proxyPass = "abc").validate().isEmpty())
    }

    @Test fun stealthWithoutProxySkipsProxyValidation() {
        val c = Config(
            mode = Mode.STEALTH_ROOT, ssid = "n", password = "12345678", proxyEnabled = false, port = 1,
            proxyDnsMode = ProxyDnsMode.CUSTOM_HTTPS,
        )
        assertTrue(c.validate().isEmpty())
    }

    @Test fun defaultDnsUsesSystemAndAdGuardIsOptIn() {
        val config = Config()
        assertNull(config.proxyDnsEndpoint())
        assertEquals(
            "https://dns.adguard-dns.com/dns-query",
            config.copy(proxyDnsMode = ProxyDnsMode.ADGUARD).proxyDnsEndpoint().toString(),
        )
    }

    @Test fun customDnsRequiresAnHttpsUrlWithoutCredentialsOrFragment() {
        val config = Config(proxyDnsMode = ProxyDnsMode.CUSTOM_HTTPS)
        for (url in listOf("", "http://dns.example/dns-query", "https:dns.example", "https://user@dns.example", "https://dns.example/#fragment")) {
            assertTrue(url, config.copy(proxyDnsUrl = url).validate().any { "HTTPS DNS URL" in it })
        }
        val valid = config.copy(proxyDnsUrl = " https://dns.example:8443/dns-query ")
        assertTrue(valid.validate().isEmpty())
        assertEquals("https://dns.example:8443/dns-query", valid.proxyDnsEndpoint().toString())
    }

    @Test fun humanBytes() {
        assertEquals("512 B", Net.humanBytes(512)); assertEquals("1.5 KB", Net.humanBytes(1536))
    }
}
