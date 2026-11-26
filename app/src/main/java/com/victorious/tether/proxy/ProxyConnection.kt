package com.victorious.tether.proxy

import java.io.Closeable
import java.io.IOException
import java.net.Socket

/** The control socket and every socket opened on its behalf have the same lifetime. */
internal class ProxyConnection(val client: Socket) : Closeable {
    private val sockets = mutableListOf<Closeable>(client)
    private var closed = false
    var usage: ProxyClients.Client? = null

    fun <T : Closeable> own(socket: T): T {
        val retained = synchronized(this) {
            if (closed) false else sockets.add(socket)
        }
        if (!retained) {
            runCatching { socket.close() }
            throw IOException("Proxy connection closed")
        }
        return socket
    }

    override fun close() {
        val open = synchronized(this) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        open.forEach { runCatching { it.close() } }
    }
}
