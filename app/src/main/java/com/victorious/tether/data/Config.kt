package com.victorious.tether.data

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom

/** How the hotspot (if any) is brought up. See GUIDE.md for the trade-offs. */
enum class Mode(val label: String, val blurb: String) {
    PROXY_LOCAL(
        "Proxy · no root",
        "Android LocalOnlyHotspot + built-in proxy. SSID is visible and chosen by Android."
    ),
    WIFI_DIRECT(
        "Wi-Fi Direct · no root",
        "Share an existing Wi-Fi connection with a password-protected Wi-Fi Direct group and the built-in proxy."
    ),
    STEALTH_ROOT(
        "Stealth · root/Shizuku",
        "Real tethering via a root or Shizuku helper. Lets you set the SSID, password and HIDE the SSID."
    ),
    PROXY_ONLY(
        "Proxy only",
        "Runs just the proxy on whatever hotspot/Wi-Fi/USB link you already have up."
    ),
    USB_PROXY(
        "USB tethering",
        "Run an authenticated proxy on the USB network created by Android’s USB tethering setting."
    ),
}

/** What runs the stealth helper. Shizuku avoids needing root. */
enum class Privilege(val label: String) { ROOT("Root (su)"), SHIZUKU("Shizuku") }

enum class ProxyDnsMode(val label: String) {
    SYSTEM("System"),
    ADGUARD("AdGuard ad blocking"),
    CUSTOM_HTTPS("Custom DNS over HTTPS"),
}

enum class UpstreamPreference(val label: String) {
    AUTOMATIC("Automatic"), ACTIVE("Active network"), WIFI("Wi-Fi"), CELLULAR("Mobile data"),
}

enum class DirectBand(val label: String, val minimumSdk: Int = 30) {
    AUTO("Automatic"), BAND_2_4("2.4 GHz"), BAND_5("5 GHz"), BAND_6("6 GHz", 36),
}

enum class ProxyIdleTimeout(val label: String, val millis: Int) {
    SHORT("3 seconds", 3_000), TEN_SECONDS("10 seconds", 10_000), THIRTY_SECONDS("30 seconds", 30_000),
    MINUTE("1 minute", 60_000), FIVE_MINUTES("5 minutes", 300_000), UNLIMITED("No idle timeout", 0),
}

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }

data class Config(
    val mode: Mode = Mode.PROXY_LOCAL,
    val privilege: Privilege = Privilege.ROOT,
    val ssid: String = "Victorious-Net",
    val password: String = "",
    val hiddenSsid: Boolean = true,
    val proxyEnabled: Boolean = true,
    val port: Int = 8282,
    val requireProxyAuth: Boolean = false,
    val proxyUser: String = "victor",
    val proxyPass: String = "",
    val autoStopMinutes: Int = 0,
    val proxyDnsMode: ProxyDnsMode = ProxyDnsMode.SYSTEM,
    val proxyDnsUrl: String = "",
    val requireProxyVpn: Boolean = false,
    val upstreamPreference: UpstreamPreference = UpstreamPreference.AUTOMATIC,
    val directBand: DirectBand = DirectBand.AUTO,
    val proxyIdleTimeout: ProxyIdleTimeout = ProxyIdleTimeout.FIVE_MINUTES,
    val holdCpuAwake: Boolean = true,
    val keepScreenOn: Boolean = false,
    val resumeAfterKill: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColors: Boolean = false,
    val haptics: Boolean = false,
) {
    /** Human-readable problems that would stop the session from starting. Empty = OK. */
    fun validate(): List<String> = buildList {
        if (mode == Mode.STEALTH_ROOT || mode == Mode.WIFI_DIRECT) {
            if (ssid.isBlank() || ssid.toByteArray().size > 32) add("SSID must be 1–32 bytes")
            if (password.length !in 8..63) add("Wi-Fi password must be 8–63 characters")
        }
        if (proxyRuns) {
            if (port !in 1024..65535) add("Proxy port must be 1024–65535")
            if (authRequired && (proxyUser.isBlank() || proxyPass.isBlank())) {
                add("Proxy username and password are required")
            }
            if (proxyDnsMode == ProxyDnsMode.CUSTOM_HTTPS && customDnsEndpoint() == null) {
                add("Enter a valid HTTPS DNS URL without a username, password, or fragment")
            }
        }
        if (autoStopMinutes < 0) add("Auto-stop cannot be negative")
    }

    /** Proxy-only sessions and shared VPN access both require a client login. */
    val proxyAuthForced: Boolean get() = mode == Mode.PROXY_ONLY || mode == Mode.USB_PROXY || requireProxyVpn

    val authRequired: Boolean get() = requireProxyAuth || proxyAuthForced

    val proxyRuns: Boolean get() = mode != Mode.STEALTH_ROOT || proxyEnabled

    internal fun proxyDnsEndpoint(): HttpUrl? = when (proxyDnsMode) {
        ProxyDnsMode.SYSTEM -> null
        ProxyDnsMode.ADGUARD -> "https://dns.adguard-dns.com/dns-query".toHttpUrl()
        ProxyDnsMode.CUSTOM_HTTPS -> requireNotNull(customDnsEndpoint()) { "Invalid HTTPS DNS URL" }
    }

    private fun customDnsEndpoint(): HttpUrl? {
        val url = proxyDnsUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return url.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }
    }
}

class ConfigStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("victorious_tether", Context.MODE_PRIVATE)

    fun load(): Config {
        val d = Config()
        val savedDnsMode = sp.getString("proxyDnsMode", null)
        val cfg = Config(
            mode = runCatching { Mode.valueOf(sp.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode),
            privilege = runCatching { Privilege.valueOf(sp.getString("privilege", d.privilege.name)!!) }.getOrDefault(d.privilege),
            ssid = sp.getString("ssid", d.ssid) ?: d.ssid,
            password = sp.getString("password", null) ?: randomSecret(12),
            hiddenSsid = sp.getBoolean("hidden", d.hiddenSsid),
            proxyEnabled = sp.getBoolean("proxyEnabled", d.proxyEnabled),
            port = sp.getInt("port", d.port),
            requireProxyAuth = sp.getBoolean("requireProxyAuth", d.requireProxyAuth),
            proxyUser = sp.getString("proxyUser", d.proxyUser) ?: d.proxyUser,
            proxyPass = sp.getString("proxyPass", null) ?: randomSecret(10),
            autoStopMinutes = sp.getInt("autoStop", d.autoStopMinutes),
            proxyDnsMode = ProxyDnsMode.entries.firstOrNull { it.name == savedDnsMode } ?: d.proxyDnsMode,
            proxyDnsUrl = sp.getString("proxyDnsUrl", d.proxyDnsUrl) ?: d.proxyDnsUrl,
            requireProxyVpn = sp.getBoolean("requireProxyVpn", d.requireProxyVpn),
            upstreamPreference = UpstreamPreference.entries.firstOrNull { it.name == sp.getString("upstreamPreference", null) } ?: d.upstreamPreference,
            directBand = DirectBand.entries.firstOrNull { it.name == sp.getString("directBand", null) } ?: d.directBand,
            proxyIdleTimeout = ProxyIdleTimeout.entries.firstOrNull { it.name == sp.getString("proxyIdleTimeout", null) } ?: d.proxyIdleTimeout,
            holdCpuAwake = sp.getBoolean("holdCpuAwake", d.holdCpuAwake),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            resumeAfterKill = sp.getBoolean("resumeAfterKill", d.resumeAfterKill),
            themeMode = ThemeMode.entries.firstOrNull { it.name == sp.getString("themeMode", null) } ?: d.themeMode,
            dynamicColors = sp.getBoolean("dynamicColors", d.dynamicColors),
            haptics = sp.getBoolean("haptics", d.haptics),
        )
        // Persist generated secrets so they stay stable between launches.
        if (!sp.contains("password") || !sp.contains("proxyPass")) save(cfg)
        return cfg
    }

    fun save(c: Config) {
        sp.edit()
            .putString("mode", c.mode.name).putString("privilege", c.privilege.name).putString("ssid", c.ssid).putString("password", c.password)
            .putBoolean("hidden", c.hiddenSsid).putBoolean("proxyEnabled", c.proxyEnabled)
            .putInt("port", c.port).putBoolean("requireProxyAuth", c.requireProxyAuth)
            .putString("proxyUser", c.proxyUser).putString("proxyPass", c.proxyPass)
            .putInt("autoStop", c.autoStopMinutes)
            .putString("proxyDnsMode", c.proxyDnsMode.name).putString("proxyDnsUrl", c.proxyDnsUrl)
            .putBoolean("requireProxyVpn", c.requireProxyVpn)
            .putString("upstreamPreference", c.upstreamPreference.name)
            .putString("directBand", c.directBand.name)
            .putString("proxyIdleTimeout", c.proxyIdleTimeout.name)
            .putBoolean("holdCpuAwake", c.holdCpuAwake)
            .putBoolean("keepScreenOn", c.keepScreenOn)
            .putBoolean("resumeAfterKill", c.resumeAfterKill)
            .putString("themeMode", c.themeMode.name)
            .putBoolean("dynamicColors", c.dynamicColors)
            .putBoolean("haptics", c.haptics)
            .apply()
    }

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun randomSecret(length: Int): String {
            val rng = SecureRandom()
            return String(CharArray(length) { ALPHABET[rng.nextInt(ALPHABET.length)] })
        }
    }
}
