package com.victorious.tether.proxy

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ClientPolicy(
    val nickname: String = "",
    val allowed: Boolean = true,
    val kibibytesPerSecond: Int = 0,
    val mebibytesPerSession: Int = 0,
) {
    internal val rateBytes get() = kibibytesPerSecond * 1024L
    internal val quotaBytes get() = mebibytesPerSession * 1024L * 1024L
}

data class ProxyClientSnapshot(
    val address: String,
    val policy: ClientPolicy,
    val activeConnections: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val lastSeenMillis: Long,
) {
    val quotaReached get() = policy.quotaBytes > 0 && bytesUp + bytesDown >= policy.quotaBytes
}

/** Session-scoped IP accounting. The same registry survives listener and route changes. */
internal class ProxyClients {
    private val entries = linkedMapOf<String, Client>()

    @Synchronized
    fun accept(address: String, connection: Closeable): Client? {
        val entry = entries[address] ?: run {
            if (entries.size >= MAX_CLIENT_IPS) return null
            Client(address).also { entries[address] = it }
        }
        return entry.takeIf { it.add(connection) }
    }

    @Synchronized
    fun snapshots() = entries.values.map(Client::snapshot).sortedByDescending { it.lastSeenMillis }

    @Synchronized
    fun restore(snapshots: List<ProxyClientSnapshot>) {
        check(entries.isEmpty())
        snapshots.take(MAX_CLIENT_IPS).forEach { snapshot ->
            entries[snapshot.address] = Client(snapshot.address, snapshot)
        }
    }

    fun update(address: String, policy: ClientPolicy) {
        require(policy.kibibytesPerSecond in 0..MAX_KIB_PER_SECOND)
        require(policy.mebibytesPerSession in 0..MAX_MIB_PER_SESSION)
        val entry = synchronized(this) { entries[address] } ?: return
        entry.update(policy)
    }

    internal class Client(private val address: String, restored: ProxyClientSnapshot? = null) {
        private val lock = Object()
        private val connections = mutableSetOf<Closeable>()
        private var policy = restored?.policy ?: ClientPolicy()
        private var up = restored?.bytesUp ?: 0L
        private var down = restored?.bytesDown ?: 0L
        private var reserved = 0L
        private var lastSeen = restored?.lastSeenMillis ?: 0L
        private var refillAt = System.nanoTime()
        private var tokens = 0.0

        fun add(connection: Closeable) = synchronized(lock) {
            if (!policy.allowed || quotaReached()) return@synchronized false
            lastSeen = System.currentTimeMillis()
            connections.add(connection)
            true
        }

        fun remove(connection: Closeable) = synchronized(lock) {
            if (connections.remove(connection)) lastSeen = System.currentTimeMillis()
            lock.notifyAll()
        }

        fun snapshot() = synchronized(lock) {
            ProxyClientSnapshot(address, policy, connections.size, up, down, lastSeen)
        }

        fun update(next: ClientPolicy) {
            val close = synchronized(lock) {
                if (next.rateBytes != policy.rateBytes) {
                    tokens = 0.0
                    refillAt = System.nanoTime()
                }
                policy = next
                lock.notifyAll()
                if (!policy.allowed || quotaReached()) connections.toList() else emptyList()
            }
            close.forEach { runCatching { it.close() } }
        }

        /** Reserve shared quota before writing so parallel connections cannot overspend it. */
        fun forward(connection: Closeable, requested: Int, upload: Boolean, partial: Boolean, write: (Int) -> Unit): Int {
            val size = reserve(connection, requested, partial)
            var written = false
            try {
                write(size)
                written = true
                return size
            } finally {
                synchronized(lock) {
                    reserved -= size
                    if (written) {
                        if (upload) up += size else down += size
                        lastSeen = System.currentTimeMillis()
                    }
                    lock.notifyAll()
                }
            }
        }

        private fun reserve(connection: Closeable, requested: Int, partial: Boolean): Int {
            synchronized(lock) {
                while (true) {
                    if (!policy.allowed || connection !in connections) throw IOException("Client blocked")
                    val quota = policy.quotaBytes
                    val available = if (quota == 0L) requested.toLong() else minOf(requested.toLong(), quota - up - down - reserved)
                    if (quotaReached() || available < 0L || (requested > 0 && available == 0L) || (!partial && available < requested)) {
                        throw IOException("Client transfer limit reached")
                    }
                    val count = available.toInt()
                    val rate = policy.rateBytes
                    if (rate > 0) {
                        val now = System.nanoTime()
                        val capacity = maxOf(rate, count.toLong()).toDouble()
                        tokens = minOf(capacity, tokens + (now - refillAt) * (rate / 1_000_000_000.0))
                        refillAt = now
                        if (tokens < count) {
                            val nanos = ((count - tokens) / rate * 1_000_000_000).toLong().coerceAtLeast(1)
                            try {
                                TimeUnit.NANOSECONDS.timedWait(lock, nanos)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IOException("Proxy stopped", e)
                            }
                            continue
                        }
                        tokens -= count
                    }
                    reserved += count
                    return count
                }
            }
        }

        private fun quotaReached() = policy.quotaBytes > 0 && up + down >= policy.quotaBytes
    }

    companion object {
        const val MAX_KIB_PER_SECOND = 1_048_576
        const val MAX_MIB_PER_SESSION = 1_048_576
        private const val MAX_CLIENT_IPS = 512
    }
}
