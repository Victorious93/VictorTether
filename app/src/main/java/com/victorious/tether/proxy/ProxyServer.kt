package com.victorious.tether.proxy

import com.victorious.tether.data.Stats
import okhttp3.Dns
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ProxyCredentials(val user: String, val pass: String)

/**
 * One-port proxy that speaks HTTP, SOCKS4a CONNECT, and SOCKS5 CONNECT and UDP ASSOCIATE,
 * chosen by sniffing the first byte of each connection.
 *
 * Safety rules, enforced here rather than left to the UI:
 *  - only sources in private/link-local ranges are served (never an open internet proxy);
 *  - destinations that resolve to loopback / wildcard / multicast are refused, so a client
 *    cannot reach services running on the phone itself;
 *  - when credentials are configured they are required on both protocols.
 */
internal class ProxyServer(
    private val port: Int,
    private val credentials: ProxyCredentials?,
    private val stats: Stats,
    /** Only tests turn this off, so they can use loopback echo servers as destinations. */
    private val blockLocalDestinations: Boolean = true,
    private val dns: Dns = Dns.SYSTEM,
    private val openUpstreamSocket: () -> Socket = ::Socket,
    private val listenAddress: InetAddress? = null,
    private val openUpstreamDatagram: () -> DatagramSocket = { DatagramSocket() },
    private val idleTimeoutMs: Int = 5 * 60_000,
) {
    private val clients = stats.clients
    private var server: ServerSocket? = null
    private val connections = mutableSetOf<ProxyConnection>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "victorious-proxy").apply { isDaemon = true }
    }

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        check(server == null && !pool.isShutdown) { "proxy has already been started" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(listenAddress, port))
        } catch (e: IOException) {
            runCatching { ss.close() }
            throw e
        }
        server = ss
        pool.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                synchronized(this) {
                    if (server == null || connections.size >= MAX_CONNECTIONS) {
                        runCatching { client.close() }
                    } else {
                        val connection = ProxyConnection(client)
                        connections.add(connection)
                        pool.execute { handle(connection) }
                    }
                }
            }
        }
    }

    fun stop() {
        val sockets = synchronized(this) {
            val listener = server ?: return
            server = null
            pool.shutdownNow()
            buildList<Closeable> {
                add(listener)
                addAll(connections)
            }.also { connections.clear() }
        }
        sockets.forEach { runCatching { it.close() } }
    }

    // ---------------------------------------------------------------- connection entry

    private fun handle(connection: ProxyConnection) {
        stats.connectionOpened()
        try {
            val client = connection.client
            if (!sourceAllowed(client.inetAddress)) return
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val output = client.getOutputStream()
            input.mark(1)
            val protocol = input.read()
            if (protocol < 0) return
            input.reset()
            when (protocol) {
                4 -> socks4(connection, input, output)
                5 -> socks5(connection, input, output)
                else -> http(connection, input, output)
            }
        } catch (_: IOException) {
            // Peer went away or timed out: nothing useful to report per connection.
        } catch (_: ProxyRefusal) {
            // Already answered the client inside the handler.
        } finally {
            connection.close()
            connection.usage?.remove(connection)
            synchronized(this) { connections.remove(connection) }
            stats.connectionClosed()
        }
    }

    private class ProxyRefusal : Exception()

    private fun sourceAllowed(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress

    private fun admit(connection: ProxyConnection, reject: () -> Unit) {
        val address = connection.client.inetAddress.hostAddress.orEmpty()
        connection.usage = clients.accept(address, connection) ?: run { reject(); throw ProxyRefusal() }
    }

    // ---------------------------------------------------------------------------- HTTP

    private fun http(connection: ProxyConnection, input: BufferedInputStream, output: OutputStream) {
        val head = readHead(input) ?: run { respond(output, 431, "Request Header Fields Too Large"); throw ProxyRefusal() }
        val lines = head.split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size != 3) { respond(output, 400, "Bad Request"); throw ProxyRefusal() }
        val (method, target, _) = parts
        val headers = lines.drop(1).filter { it.isNotEmpty() }

        if (credentials != null && !httpAuthorised(headers)) {
            respond(output, 407, "Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"Victorious Tether\"")
            throw ProxyRefusal()
        }
        admit(connection) { respond(output, 403, "Client access restricted") }

        if (method.equals("CONNECT", ignoreCase = true)) {
            val idx = target.lastIndexOf(':')
            val host = if (idx > 0) target.substring(0, idx).trim('[', ']') else target
            val destPort = if (idx > 0) target.substring(idx + 1).toIntOrNull() else null
            if (destPort == null || destPort !in 1..65535) { respond(output, 400, "Bad Request"); throw ProxyRefusal() }
            val upstream = connectOrRespond(connection, host, destPort) { code, msg -> respond(output, code, msg) }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush()
            pipe(connection, input, output, upstream)
        } else {
            val uri = runCatching { URI(target) }.getOrNull()
            if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host == null) {
                respond(output, 400, "Bad Request", body = "Only absolute http:// URLs or CONNECT are supported.")
                throw ProxyRefusal()
            }
            val destPort = if (uri.port > 0) uri.port else 80
            val upstream = connectOrRespond(connection, uri.host, destPort) { code, msg -> respond(output, code, msg) }
            val path = buildString {
                append(uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            }
            val out = upstream.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\n")
            for (h in headers) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name in HOP_BY_HOP || name == "proxy-authorization") continue
                sb.append(h).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            val forwardedHead = sb.toString().toByteArray(Charsets.ISO_8859_1)
            forward(connection, forwardedHead.size, upload = true) { length ->
                out.write(forwardedHead, 0, length)
                out.flush()
            }
            pipe(connection, input, output, upstream)
        }
    }

    private fun httpAuthorised(headers: List<String>): Boolean {
        val value = headers.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return false
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(value.substring(6).trim()), Charsets.UTF_8) }
            .getOrNull() ?: return false
        return constantTimeEquals(decoded, "${credentials!!.user}:${credentials.pass}")
    }

    /** Reads up to the blank line ending an HTTP header block; null if it exceeds 16 KiB. */
    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var matched = 0
        while (sb.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) throw IOException("eof in header")
            sb.append(b.toChar())
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == 4) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    private fun respond(out: OutputStream, code: Int, reason: String, extraHeader: String? = null, body: String = "") {
        val payload = body.toByteArray()
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        extraHeader?.let { sb.append(it).append("\r\n") }
        sb.append("Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
        runCatching { out.write(sb.toString().toByteArray()); out.write(payload); out.flush() }
    }

    // ----------------------------------------------------------------------------- SOCKS

    private fun socks4(connection: ProxyConnection, input: BufferedInputStream, output: OutputStream) {
        val din = DataInputStream(input)
        din.readUnsignedByte()
        val command = din.readUnsignedByte()
        val port = din.readUnsignedShort()
        val address = ByteArray(4).also(din::readFully)
        din.readSocks4String() // User ID is not a credential.
        val domainNameFollows = address.take(3).all { it == 0.toByte() } && address[3] != 0.toByte()
        val host = if (domainNameFollows) din.readSocks4String() else InetAddress.getByAddress(address).hostAddress.orEmpty()
        fun reply(code: Int) {
            output.write(byteArrayOf(0, code.toByte(), 0, 0, 0, 0, 0, 0))
            output.flush()
        }
        if (credentials != null || command != 1 || port == 0 || host.isBlank()) {
            reply(91)
            return
        }
        admit(connection) { reply(91) }
        val upstream = connectOrRespond(connection, host, port) { _, _ -> reply(91) }
        reply(90)
        pipe(connection, input, output, upstream)
    }

    private fun DataInputStream.readSocks4String(): String {
        val bytes = ByteArray(255)
        for (i in 0..bytes.size) {
            val next = readUnsignedByte()
            if (next == 0) return String(bytes, 0, i, Charsets.UTF_8)
            if (i < bytes.size) bytes[i] = next.toByte()
        }
        throw IOException("SOCKS4 field too long")
    }

    private fun socks5(connection: ProxyConnection, input: BufferedInputStream, output: OutputStream) {
        val din = DataInputStream(input)
        din.readUnsignedByte() // version, already sniffed
        val methods = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
        val wanted = if (credentials != null) 0x02 else 0x00
        if (wanted.toByte() !in methods) {
            output.write(byteArrayOf(5, 0xFF.toByte())); output.flush(); throw ProxyRefusal()
        }
        output.write(byteArrayOf(5, wanted.toByte())); output.flush()

        if (credentials != null) {
            val authVersion = din.readUnsignedByte()
            val user = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
            val pass = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
            val validFields = constantTimeEquals(String(user, Charsets.UTF_8), credentials.user) and
                constantTimeEquals(String(pass, Charsets.UTF_8), credentials.pass)
            val ok = authVersion == 1 && validFields
            output.write(byteArrayOf(1, if (ok) 0 else 1)); output.flush()
            if (!ok) throw ProxyRefusal()
        }

        val version = din.readUnsignedByte()
        val cmd = din.readUnsignedByte()
        val reserved = din.readUnsignedByte()
        if (version != 5 || reserved != 0) { socksReply(output, 0x01); return }
        val host = try { din.readSocksHost() } catch (_: SocksAddressType) { socksReply(output, 0x08); return }
        val destPort = din.readUnsignedShort()
        if (cmd != 0x01 && cmd != 0x03) { socksReply(output, 0x07); return }
        admit(connection) { socksReply(output, 0x02) }
        if (cmd == 0x03) {
            udpAssociate(connection, input, output, destPort)
            return
        }
        if (destPort == 0 || host.isBlank()) { socksReply(output, 0x01); return }

        val upstream = connectOrRespond(connection, host, destPort) { code, _ ->
            socksReply(output, if (code == 403) 0x02 else if (code == 504) 0x06 else 0x04)
        }
        socksReply(output, 0x00)
        pipe(connection, input, output, upstream)
    }

    private fun socksReply(out: OutputStream, code: Int, bound: InetSocketAddress? = null) {
        val address = bound?.socksAddress() ?: byteArrayOf(1, 0, 0, 0, 0, 0, 0)
        out.write(byteArrayOf(5, code.toByte(), 0) + address)
        out.flush()
    }

    private fun udpAssociate(connection: ProxyConnection, input: InputStream, output: OutputStream, clientPort: Int) {
        val client = connection.client
        val relaySocket: DatagramSocket
        val outgoing: DatagramSocket
        try {
            relaySocket = connection.own(DatagramSocket(null)).apply { bind(InetSocketAddress(client.localAddress, 0)) }
            outgoing = connection.own(openUpstreamDatagram())
        } catch (_: IOException) {
            socksReply(output, 0x01)
            return
        }
        val activity = AtomicLong(System.nanoTime())
        val relay = SocksDatagrams(relaySocket, outgoing, client.inetAddress, clientPort, ::resolveDestination) { length, upload, send ->
            forward(connection, length, upload, write = send)
            activity.set(System.nanoTime())
        }
        synchronized(this) {
            if (server == null) return
            for (work in listOf(relay::upload, relay::download)) {
                pool.execute {
                    try { work() } catch (_: IOException) { } finally { connection.close() }
                }
            }
        }
        socksReply(output, 0, relaySocket.localSocketAddress as InetSocketAddress)
        // The association is owned by this TCP connection. Idle UDP is timed separately.
        client.soTimeout = idleTimeoutMs
        readUntilIdle(input, activity)
    }

    // ------------------------------------------------------------------ shared plumbing

    /** Resolves and connects, refusing local destinations; reports failures via [fail] then aborts. */
    private fun resolveDestination(host: String): InetAddress? {
        if (host.isBlank()) return null
        return dns.lookup(host).firstOrNull {
            !blockLocalDestinations || (!it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress &&
                NetworkInterface.getByInetAddress(it) == null)
        }
    }

    private fun connectOrRespond(connection: ProxyConnection, host: String, destPort: Int, fail: (Int, String) -> Unit): Socket {
        val addr = try {
            resolveDestination(host)
                ?: run { fail(403, "Forbidden"); throw ProxyRefusal() }
        } catch (_: UnknownHostException) {
            fail(502, "Bad Gateway"); throw ProxyRefusal()
        }
        if (destPort !in 1..65535) { fail(400, "Bad Request"); throw ProxyRefusal() }
        try {
            val s = connection.own(openUpstreamSocket())
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, destPort), CONNECT_TIMEOUT_MS)
            return s
        } catch (_: SocketTimeoutException) {
            fail(504, "Gateway Timeout"); throw ProxyRefusal()
        } catch (_: IOException) {
            fail(502, "Bad Gateway"); throw ProxyRefusal()
        }
    }

    /** Full-duplex copy; half-closes propagate so request/response protocols terminate cleanly. */
    private fun pipe(connection: ProxyConnection, clientIn: InputStream, clientOut: OutputStream, upstream: Socket) {
        val client = connection.client
        client.soTimeout = idleTimeoutMs
        upstream.soTimeout = idleTimeoutMs
        val activity = AtomicLong(System.nanoTime())
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val uplink = synchronized(this) {
            if (server == null) return
            pool.submit {
                copy(connection, clientIn, upOut, activity, upload = true)
                runCatching { upstream.shutdownOutput() }
            }
        }
        copy(connection, upIn, clientOut, activity, upload = false)
        runCatching { client.shutdownOutput() }
        runCatching { if (idleTimeoutMs == 0) uplink.get() else uplink.get(idleTimeoutMs.toLong(), TimeUnit.MILLISECONDS) }
        uplink.cancel(true)
    }

    private fun readUntilIdle(src: InputStream, activity: AtomicLong, buffer: ByteArray? = null): Int {
        while (true) {
            try {
                return if (buffer == null) src.read() else src.read(buffer)
            } catch (e: SocketTimeoutException) {
                if (idleTimeoutMs == 0 || System.nanoTime() - activity.get() >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs.toLong())) throw e
            }
        }
    }

    private fun copy(connection: ProxyConnection, src: InputStream, dst: OutputStream, activity: AtomicLong, upload: Boolean) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = readUntilIdle(src, activity, buf)
                if (n < 0) break
                val forwarded = forward(connection, n, upload, partial = true) { length ->
                    dst.write(buf, 0, length)
                    dst.flush()
                }
                activity.set(System.nanoTime())
                if (forwarded < n) {
                    connection.close()
                    break
                }
            }
        } catch (_: IOException) {
            connection.close()
        }
    }

    private fun forward(connection: ProxyConnection, count: Int, upload: Boolean, partial: Boolean = false, write: (Int) -> Unit): Int {
        val length = checkNotNull(connection.usage).forward(connection, count, upload, partial, write)
        if (upload) stats.addUp(length.toLong()) else stats.addDown(length.toLong())
        return length
    }

    private fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_CONNECTIONS = 96
        const val MAX_HEAD = 16 * 1024
        val HOP_BY_HOP = setOf("connection", "proxy-connection", "keep-alive", "te", "trailer", "upgrade")
    }
}
