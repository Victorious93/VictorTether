package com.victorious.tether.data

import com.victorious.tether.proxy.ClientPolicy
import com.victorious.tether.proxy.ProxyClients
import com.victorious.tether.proxy.ProxyClientSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class HotspotInfo(
    val ssid: String,
    val password: String,
    val hidden: Boolean,
    val proxyAddress: InetAddress? = null,
)

data class IpCandidate(val iface: String, val address: String, val looksLikeAccessPoint: Boolean)

data class StatsSnapshot(val activeConnections: Int = 0, val totalConnections: Long = 0, val bytesUp: Long = 0, val bytesDown: Long = 0)

sealed interface Status {
    data object Idle : Status
    data object Starting : Status
    data class Reconnecting(val message: String) : Status
    data object Stopping : Status
    data class Running(
        val config: Config,
        val hotspot: HotspotInfo?,
        val addresses: List<IpCandidate>,
        val startedAtMillis: Long,
        val proxyRoute: String? = null,
    ) : Status
    data class Failed(val message: String) : Status
}

/** Traffic counters written by proxy threads, sampled once a second by the service. */
class Stats {
    internal val clients = ProxyClients()
    private val active = AtomicInteger()
    private val total = AtomicLong()
    private val up = AtomicLong()
    private val down = AtomicLong()

    fun connectionOpened() { active.incrementAndGet(); total.incrementAndGet() }
    fun connectionClosed() { active.decrementAndGet() }
    fun addUp(n: Long) { up.addAndGet(n) }
    fun addDown(n: Long) { down.addAndGet(n) }
    fun snapshot() = StatsSnapshot(active.get(), total.get(), up.get(), down.get())
}

/** Process-wide state shared by the service, the tile and the UI. */
object TetherState {
    private val _status = MutableStateFlow<Status>(Status.Idle)
    private val _stats = MutableStateFlow(StatsSnapshot())
    private val _clients = MutableStateFlow<List<ProxyClientSnapshot>>(emptyList())
    private var editClient: ((String, ClientPolicy) -> Unit)? = null
    val status: StateFlow<Status> get() = _status
    val stats: StateFlow<StatsSnapshot> get() = _stats
    val clients: StateFlow<List<ProxyClientSnapshot>> get() = _clients

    fun set(s: Status) { _status.value = s }
    fun publish(s: StatsSnapshot) { _stats.value = s }
    internal fun publishClients(clients: List<ProxyClientSnapshot>) { _clients.value = clients }
    internal fun setClientEditor(editor: ((String, ClientPolicy) -> Unit)?) { editClient = editor }
    fun updateClient(address: String, policy: ClientPolicy) { editClient?.invoke(address, policy) }
}
