package com.victorious.tether.hotspot

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.victorious.tether.data.Config
import com.victorious.tether.data.HotspotInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What the user sees about a Wi-Fi Direct group. */
data class DirectGroup(
    val networkName: String,
    val passphrase: String,
    val isOwner: Boolean,
    val clients: List<DirectPeer>,
)

data class DirectPeer(val name: String, val address: String, val status: String)

data class DirectSupport(val hasFeature: Boolean, val enabled: Boolean?)

/**
 * Thin coroutine wrapper over [WifiP2pManager] used by both the hotspot backend and the tools
 * panel. Callers must already hold NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION; the UI
 * requests them before any call, so lint's MissingPermission is suppressed here deliberately.
 */
@SuppressLint("MissingPermission")
class WifiDirect(private val context: Context) {
    private val manager: WifiP2pManager? = context.applicationContext.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private fun channel(): WifiP2pManager.Channel {
        val m = manager ?: throw HotspotException("Wi-Fi Direct is not supported on this phone")
        channel?.let { return it }
        val created = m.initialize(context.applicationContext, Looper.getMainLooper(), null)
            ?: throw HotspotException("Could not open a Wi-Fi Direct channel")
        channel = created
        return created
    }

    fun close() {
        runCatching { channel?.close() }
        channel = null
    }

    suspend fun support(): DirectSupport {
        val has = context.packageManager.hasSystemFeature("android.hardware.wifi.direct")
        val m = manager ?: return DirectSupport(has, null)
        val enabled = runCatching {
            suspendCancellableCoroutine<Boolean> { cont ->
                m.requestP2pState(channel()) { state -> if (cont.isActive) cont.resume(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
            }
        }.getOrNull()
        return DirectSupport(has, enabled)
    }

    /** Creates the group; if the requested name/passphrase is rejected, lets Android choose. */
    suspend fun createGroup(name: String?, passphrase: String?) {
        val m = manager ?: throw HotspotException("Wi-Fi Direct is not supported on this phone")
        val ch = channel()
        removeGroupQuietly()
        val cfg = runCatching {
            if (name != null && passphrase != null) {
                WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(passphrase)
                    .enablePersistentMode(false)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
            } else null
        }.getOrNull()

        // A null config means no custom name was requested, so go straight to the system default.
        val customFailure: Int? = if (cfg != null) action { m.createGroup(ch, cfg, it) } else NO_CONFIG
        if (customFailure != null) {
            // OEMs often reject custom names; fall back so the user still gets a working group.
            val fallback = action { m.createGroup(ch, it) }
            if (fallback != null) throw HotspotException("Wi-Fi Direct could not create a group: ${reason(fallback)}")
        }
    }

    suspend fun groupInfo(): DirectGroup? {
        val m = manager ?: return null
        val g = suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
            m.requestGroupInfo(channel()) { if (cont.isActive) cont.resume(it) }
        } ?: return null
        return DirectGroup(
            networkName = g.networkName.orEmpty(),
            passphrase = g.passphrase.orEmpty(),
            isOwner = g.isGroupOwner,
            clients = g.clientList.orEmpty().map { it.toPeer() },
        )
    }

    /** Waits for the system to report the group after [createGroup]; null if it never appears. */
    suspend fun awaitGroup(): DirectGroup? {
        repeat(20) {
            groupInfo()?.takeIf { it.networkName.isNotEmpty() }?.let { return it }
            delay(500)
        }
        return null
    }

    suspend fun removeGroup() {
        val m = manager ?: return
        action { m.removeGroup(channel(), it) }
    }

    private suspend fun removeGroupQuietly() { runCatching { removeGroup() }; delay(300) }

    suspend fun discoverPeers(seconds: Int = 5): List<DirectPeer> {
        val m = manager ?: throw HotspotException("Wi-Fi Direct is not supported on this phone")
        val ch = channel()
        val err = action { m.discoverPeers(ch, it) }
        if (err != null) throw HotspotException("Peer discovery failed: ${reason(err)}")
        delay(seconds * 1000L)
        val peers = suspendCancellableCoroutine<List<DirectPeer>> { cont ->
            m.requestPeers(ch) { list -> if (cont.isActive) cont.resume(list.deviceList.map { it.toPeer() }) }
        }
        runCatching { action { m.stopPeerDiscovery(ch, it) } }
        return peers
    }

    /** Runs a P2P action; returns null on success or the failure reason code. */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int? =
        suspendCancellableCoroutine { cont ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(null) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
            })
        }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "system busy (turn Wi-Fi Direct/hotspot off and retry)"
        WifiP2pManager.ERROR -> "internal error"
        else -> "code $code"
    }

    private fun WifiP2pDevice.toPeer() = DirectPeer(
        name = deviceName.orEmpty().ifBlank { "(unnamed)" },
        address = deviceAddress.orEmpty(),
        status = when (status) {
            WifiP2pDevice.CONNECTED -> "connected"
            WifiP2pDevice.INVITED -> "invited"
            WifiP2pDevice.FAILED -> "failed"
            WifiP2pDevice.UNAVAILABLE -> "unavailable"
            else -> "available"
        },
    )

    companion object {
        private const val NO_CONFIG = -1

        /** Android requires "DIRECT-" + two characters + optional suffix, 32 bytes max. */
        fun networkNameFor(ssid: String): String {
            val full = if (ssid.startsWith("DIRECT-") && ssid.length >= 9) ssid else "DIRECT-vt-$ssid"
            return full.toByteArray().let { if (it.size <= 32) full else String(it, 0, 32, Charsets.UTF_8).trimEnd('�') }
        }
    }
}

/**
 * Wi-Fi Direct group as a hotspot. Clients still need the proxy, as with LocalOnlyHotspot.
 *
 * Watches the system's connection-changed broadcast so that if the group disappears (user
 * removed it, Wi-Fi switched off, another app took the radio) [onExternalStop] ends the session
 * instead of leaving the UI saying ONLINE.
 */
class WifiDirectBackend(
    private val context: Context,
    private val onExternalStop: () -> Unit,
) : HotspotBackend {
    private val direct = WifiDirect(context)
    // Main dispatcher on purpose: P2P callbacks arrive on the main looper, so never block it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Only true between a successful start and our own stop, so setup/teardown events are ignored. */
    @Volatile private var active = false
    private var receiver: BroadcastReceiver? = null

    override suspend fun start(config: Config): HotspotInfo {
        direct.createGroup(WifiDirect.networkNameFor(config.ssid), config.password)
        val g = direct.awaitGroup() ?: throw HotspotException("Wi-Fi Direct group did not come up")
        watchForGroupLoss()
        active = true
        return HotspotInfo(g.networkName, g.passphrase, hidden = false)
    }

    override suspend fun stop() {
        active = false
        unwatch()
        runCatching { direct.removeGroup() }
        direct.close()
    }

    private fun watchForGroupLoss() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val formed = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)?.groupFormed
                if (!shouldCheckForLoss(active, formed)) return
                // The broadcast can flicker; confirm with the system before ending the session.
                scope.launch {
                    delay(CONFIRM_DELAY_MS)
                    if (active && runCatching { direct.groupInfo() }.getOrNull() == null) {
                        active = false
                        onExternalStop()
                    }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context.applicationContext, r,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unwatch() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        private const val CONFIRM_DELAY_MS = 1500L

        /** A loss is only suspected while running and when the system says the group is not formed. */
        fun shouldCheckForLoss(active: Boolean, groupFormed: Boolean?): Boolean = active && groupFormed == false
    }
}
