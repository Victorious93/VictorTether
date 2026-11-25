package com.victorious.tether.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.victorious.tether.data.Config
import com.victorious.tether.data.HotspotInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Non-root backend built on the public [WifiManager.startLocalOnlyHotspot] API.
 *
 * Platform limits (not app limits): Android picks the SSID and passphrase, the SSID is always
 * broadcast, and clients get no internet routing, which is why the proxy exists.
 */
class LocalOnlyBackend(context: Context, private val onExternalStop: () -> Unit) : HotspotBackend {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Suppress("DEPRECATION")
    override suspend fun start(config: Config): HotspotInfo = suspendCancellableCoroutine { cont ->
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                val sa = res.softApConfiguration
                if (cont.isActive) cont.resume(HotspotInfo(sa.ssid.orEmpty(), sa.passphrase.orEmpty(), hidden = false))
            }

            override fun onStopped() { onExternalStop() }

            override fun onFailed(reason: Int) {
                val why = when (reason) {
                    ERROR_NO_CHANNEL -> "no usable Wi-Fi channel"
                    ERROR_TETHERING_DISALLOWED -> "tethering is disallowed on this device/profile"
                    ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is in an incompatible mode (turn off other hotspot/Wi-Fi Direct)"
                    else -> "generic failure (is Location turned on in system settings?)"
                }
                if (cont.isActive) cont.resumeWithException(HotspotException("LocalOnlyHotspot failed: $why"))
            }
        }
        try {
            wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            cont.resumeWithException(HotspotException("Missing Wi-Fi/Nearby-devices permission", e))
        } catch (e: IllegalStateException) {
            cont.resumeWithException(HotspotException("Wi-Fi hotspot already active elsewhere", e))
        }
        cont.invokeOnCancellation { runCatching { reservation?.close() } }
    }

    override suspend fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }
}
