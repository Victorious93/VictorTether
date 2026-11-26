package com.victorious.tether.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.victorious.tether.data.Config
import com.victorious.tether.data.DirectBand
import com.victorious.tether.data.HotspotInfo
import com.victorious.tether.service.fingerprint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
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
    suspend fun createGroup(name: String, passphrase: String, band: DirectBand) {
        val m = manager ?: throw HotspotException("Wi-Fi Direct is not supported on this phone")
        val ch = channel()
        if (Build.VERSION.SDK_INT < band.minimumSdk) throw HotspotException("${band.label} Wi-Fi Direct requires a newer Android version.")
        val operatingBand = when (band) {
            DirectBand.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            DirectBand.BAND_2_4 -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
            DirectBand.BAND_5 -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
            // WifiP2pConfig.GROUP_OWNER_BAND_6GHZ, introduced in API 36 (compileSdk 35).
            DirectBand.BAND_6 -> 3
        }
        val cfg = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(operatingBand)
                .enablePersistentMode(false)
                .build()
        }.getOrNull()

        val customFailure: Int? = if (cfg != null) action { m.createGroup(ch, cfg, it) } else NO_CONFIG
        if (customFailure != null) {
            if (band != DirectBand.AUTO) {
                val detail = if (customFailure == NO_CONFIG) "configuration rejected" else reason(customFailure)
                throw HotspotException("Android could not start Wi-Fi Direct on ${band.label} ($detail). Try Automatic.")
            }
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

    suspend fun groupOwnerAddress(): Inet4Address? {
        val m = manager ?: return null
        return suspendCancellableCoroutine { cont ->
            m.requestConnectionInfo(channel()) { info ->
                val address = info.groupOwnerAddress as? Inet4Address
                val owner = info.groupFormed && info.isGroupOwner && address?.isAnyLocalAddress == false
                if (cont.isActive) cont.resume(address.takeIf { owner })
            }
        }
    }

    /** Waits for the system to report the group after [createGroup]; null if it never appears. */
    suspend fun awaitGroup(): DirectGroup? {
        repeat(20) {
            groupInfo()?.takeIf { it.isOwner && it.networkName.isNotEmpty() }?.let { return it }
            delay(500)
        }
        return null
    }

    suspend fun removeGroup() {
        val m = manager ?: return
        action { m.removeGroup(channel(), it) }
    }

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

/** Owns a Wi-Fi Direct group for proxy clients. */
class WifiDirectBackend(context: Context, private val previousGroupKey: String? = null) : HotspotBackend {
    private val direct = WifiDirect(context)
    private var ownsGroup = false
    private var groupName: String? = null

    override suspend fun start(config: Config): HotspotInfo {
        val existing = withTimeoutOrNull(3_000) { ExistingGroup(direct.groupInfo()) }
            ?: throw HotspotException("Android did not report the Wi-Fi Direct group state.")
        val group = if (existing.group != null) {
            if (!matchesRecoveredGroup(existing.group)) {
                throw HotspotException("A Wi-Fi Direct group is already active. Close it before starting VictorTether.")
            }
            ownsGroup = true
            existing.group
        } else {
            withContext(NonCancellable) {
                val accepted = withTimeoutOrNull(6_000) {
                    direct.createGroup(WifiDirect.networkNameFor(config.ssid), config.password, config.directBand)
                    true
                } ?: false
                if (!accepted) throw HotspotException("Android did not respond to the Wi-Fi Direct group request.")
                ownsGroup = true
            }
            withTimeoutOrNull(12_000) { direct.awaitGroup() }
                ?: throw HotspotException("Android could not bring up the Wi-Fi Direct group.")
        }
        groupName = group.networkName
        if (group.passphrase.isBlank()) throw HotspotException("Android did not provide the Wi-Fi Direct group password.")
        val address = withTimeoutOrNull(4_000) {
            var ownerAddress = direct.groupOwnerAddress()
            while (ownerAddress == null) {
                delay(250)
                ownerAddress = direct.groupOwnerAddress()
            }
            ownerAddress
        } ?: throw HotspotException("The Wi-Fi Direct group has no usable IPv4 address.")
        return HotspotInfo(group.networkName, group.passphrase, hidden = false, proxyAddress = address)
    }

    suspend fun groupIsActive(name: String): Boolean? = withTimeoutOrNull(1_500) {
        val group = direct.groupInfo()
        group?.isOwner == true && group.networkName == name
    }

    override suspend fun stop() {
        try {
            if (ownsGroup || previousGroupKey != null) withTimeoutOrNull(4_000) {
                val group = direct.groupInfo()
                val ours = if (ownsGroup) (groupName == null && group == null) ||
                    (group?.isOwner == true && (groupName == null || group.networkName == groupName))
                    else matchesRecoveredGroup(group)
                if (ours) direct.removeGroup()
            }
        } finally {
            ownsGroup = false
            groupName = null
            direct.close()
        }
    }

    private fun matchesRecoveredGroup(group: DirectGroup?) = previousGroupKey != null && group?.isOwner == true &&
        previousGroupKey == directGroupKey(group.networkName, group.passphrase)

    private data class ExistingGroup(val group: DirectGroup?)
}

internal fun directGroupKey(name: String, passphrase: String) = fingerprint("${name.length}:$name$passphrase")
