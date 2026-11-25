package com.victorious.tether.hotspot

import android.content.Context
import android.util.Base64
import com.victorious.tether.data.Config
import com.victorious.tether.data.HotspotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Stealth backend. Runs [com.victorious.tether.root.SoftApMain] as uid 0 through `su` so it can
 * call the system-only SoftAp/Tethering APIs, which is the only way to set a hidden SSID.
 *
 * Arguments are base64-encoded so no user text ever reaches a shell unquoted.
 */
class RootBackend(private val context: Context) : HotspotBackend {

    override suspend fun start(config: Config): HotspotInfo {
        val out = runHelper(
            "start",
            b64(config.ssid), b64(config.password), config.hiddenSsid.toString(),
        )
        if (!out.startsWith("OK")) throw HotspotException("Root helper: ${out.ifBlank { "no output" }}")
        return HotspotInfo(config.ssid, config.password, config.hiddenSsid)
    }

    override suspend fun stop() {
        runCatching { runHelper("stop") }
    }

    private suspend fun runHelper(vararg args: String): String = withContext(Dispatchers.IO) {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' exec app_process /system/bin com.victorious.tether.root.SoftApMain " +
            args.joinToString(" ") { "'$it'" }
        val proc = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw HotspotException("Root (su) is not available on this device", e)
        }
        if (!proc.waitFor(45, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw HotspotException("Root helper timed out (did the root prompt appear?)")
        }
        proc.inputStream.bufferedReader().readText().trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
