package com.victorious.tether

import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.util.Net
import org.junit.Assert.assertEquals
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
        val c = Config(mode = Mode.STEALTH_ROOT, ssid = "n", password = "12345678", proxyEnabled = false, port = 1)
        assertTrue(c.validate().isEmpty())
    }

    @Test fun humanBytes() {
        assertEquals("512 B", Net.humanBytes(512)); assertEquals("1.5 KB", Net.humanBytes(1536))
    }
}
