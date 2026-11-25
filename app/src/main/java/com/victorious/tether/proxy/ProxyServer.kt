package com.victorious.tether.proxy

import com.victorious.tether.data.Stats
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProxyCredentials(val user: String, val pass: String)

/**
 * One-port proxy that speaks HTTP (absolute-URI forwarding + CONNECT) and SOCKS5 (CONNECT),
 * chosen by sniffing the first byte of each connection.
 *
 * Safety rules, enforced here rather than left to the UI:
 *  - only sources in private/link-local ranges are served (never an open internet proxy);
 *  - destinations that resolve to loopback / wildcard / multicast are refused, so a client
 *    cannot reach services running on the phone itself;
 *  - when credentials are configured they are required on both protocols.
 */
class ProxyServer(
    private val port: Int,
    private val credentials: ProxyCredentials?,
    private val stats: Stats,
    /** Only tests turn this off, so they can use loopback echo servers as destinations. */
    private val blockLocalDestinations: Boolean = true,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "victorious-proxy").apply { isDaemon = true }
    }

    @Throws(IOException::class)
    fun start() {
        check(running.compareAndSet(false, true)) { "already running" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            running.set(false)
            ss.close()
            throw e
        }
        server = ss
        pool.execute {
            while (running.get()) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- connection entry

    private fun handle(client: Socket) {
        stats.connectionOpened()
        try {
            client.use { c ->
                if (!sourceAllowed(c.inetAddress)) return
                c.soTimeout = HANDSHAKE_TIMEOUT_MS
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream(), 16 * 1024)
                val output = c.getOutputStream()
                input.mark(1)
                val first = input.read()
                if (first < 0) return
                input.reset()
                if (first == 0x05) socks5(c, input, output) else http(c, input, output)
            }
        } catch (_: IOException) {
            // Peer went away or timed out: nothing useful to report per connection.
        } catch (_: ProxyRefusal) {
            // Already answered the client inside the handler.
        } finally {
            stats.connectionClosed()
        }
    }

    private class ProxyRefusal : Exception()

    private fun sourceAllowed(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress

    // ---------------------------------------------------------------------------- HTTP

    private fun http(client: Socket, input: BufferedInputStream, output: OutputStream) {
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

        if (method.equals("CONNECT", ignoreCase = true)) {
            val idx = target.lastIndexOf(':')
            val host = if (idx > 0) target.substring(0, idx).trim('[', ']') else target
            val destPort = if (idx > 0) target.substring(idx + 1).toIntOrNull() else null
            if (destPort == null || destPort !in 1..65535) { respond(output, 400, "Bad Request"); throw ProxyRefusal() }
            val upstream = connectOrRespond(host, destPort) { code, msg -> respond(output, code, msg) }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush()
            pipe(client, input, output, upstream)
        } else {
            val uri = runCatching { URI(target) }.getOrNull()
            if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host == null) {
                respond(output, 400, "Bad Request", body = "Only absolute http:// URLs or CONNECT are supported.")
                throw ProxyRefusal()
            }
            val destPort = if (uri.port > 0) uri.port else 80
            val upstream = connectOrRespond(uri.host, destPort) { code, msg -> respond(output, code, msg) }
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
            out.write(forwardedHead); out.flush()
            stats.addUp(forwardedHead.size.toLong())
            pipe(client, input, output, upstream)
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

    // --------------------------------------------------------------------------- SOCKS5

    private fun socks5(client: Socket, input: BufferedInputStream, output: OutputStream) {
        val din = java.io.DataInputStream(input)
        din.readUnsignedByte() // version, already sniffed
        val methods = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
        val wanted = if (credentials != null) 0x02 else 0x00
        if (wanted.toByte() !in methods) {
            output.write(byteArrayOf(5, 0xFF.toByte())); output.flush(); throw ProxyRefusal()
        }
        output.write(byteArrayOf(5, wanted.toByte())); output.flush()

        if (credentials != null) {
            din.readUnsignedByte() // sub-negotiation version
            val user = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
            val pass = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
            val ok = constantTimeEquals(
                String(user, Charsets.UTF_8) + ":" + String(pass, Charsets.UTF_8),
                "${credentials.user}:${credentials.pass}",
            )
            output.write(byteArrayOf(1, if (ok) 0 else 1)); output.flush()
            if (!ok) throw ProxyRefusal()
        }

        din.readUnsignedByte() // version
        val cmd = din.readUnsignedByte()
        din.readUnsignedByte() // reserved
        val host = when (din.readUnsignedByte()) {
            0x01 -> ByteArray(4).also { din.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            0x03 -> ByteArray(din.readUnsignedByte()).also { din.readFully(it) }.toString(Charsets.UTF_8)
            0x04 -> ByteArray(16).also { din.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            else -> { socksReply(output, 0x08); throw ProxyRefusal() }
        }
        val destPort = din.readUnsignedShort()
        if (cmd != 0x01) { socksReply(output, 0x07); throw ProxyRefusal() } // CONNECT only; no BIND/UDP

        val upstream = connectOrRespond(host, destPort) { code, _ ->
            socksReply(output, if (code == 403) 0x02 else if (code == 504) 0x06 else 0x04)
        }
        socksReply(output, 0x00)
        pipe(client, input, output, upstream)
    }

    private fun socksReply(out: OutputStream, code: Int) {
        runCatching { out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() }
    }

    // ------------------------------------------------------------------ shared plumbing

    /** Resolves and connects, refusing local destinations; reports failures via [fail] then aborts. */
    private fun connectOrRespond(host: String, destPort: Int, fail: (Int, String) -> Unit): Socket {
        val addr = try {
            InetAddress.getAllByName(host).firstOrNull {
                !blockLocalDestinations || (!it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress)
            }
                ?: run { fail(403, "Forbidden"); throw ProxyRefusal() }
        } catch (_: java.net.UnknownHostException) {
            fail(502, "Bad Gateway"); throw ProxyRefusal()
        }
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, destPort), CONNECT_TIMEOUT_MS)
            return s
        } catch (e: SocketTimeoutException) {
            runCatching { s.close() }; fail(504, "Gateway Timeout"); throw ProxyRefusal()
        } catch (e: IOException) {
            runCatching { s.close() }; fail(502, "Bad Gateway"); throw ProxyRefusal()
        }
    }

    /** Full-duplex copy; half-closes propagate so request/response protocols terminate cleanly. */
    private fun pipe(client: Socket, clientIn: InputStream, clientOut: OutputStream, upstream: Socket) {
        client.soTimeout = IDLE_TIMEOUT_MS
        upstream.soTimeout = IDLE_TIMEOUT_MS
        upstream.use { up ->
            val upIn = up.getInputStream()
            val upOut = up.getOutputStream()
            val uplink = pool.submit {
                copy(clientIn, upOut) { stats.addUp(it.toLong()) }
                runCatching { up.shutdownOutput() }
            }
            copy(upIn, clientOut) { stats.addDown(it.toLong()) }
            runCatching { client.shutdownOutput() }
            runCatching { uplink.get(IDLE_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) }
            uplink.cancel(true)
        }
    }

    private inline fun copy(src: InputStream, dst: OutputStream, onBytes: (Int) -> Unit) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n); dst.flush()
                onBytes(n)
            }
        } catch (_: IOException) {
            // Either side closing or idling out ends this direction.
        }
    }

    private fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 5 * 60_000
        const val MAX_HEAD = 16 * 1024
        val HOP_BY_HOP = setOf("connection", "proxy-connection", "keep-alive", "te", "trailer", "upgrade")
    }
}
