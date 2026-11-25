package com.victorious.tether

import com.victorious.tether.data.Stats
import com.victorious.tether.proxy.ProxyCredentials
import com.victorious.tether.proxy.ProxyServer
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Base64

/** Exercises the real proxy over loopback sockets: HTTP forward, CONNECT, SOCKS5, auth, SSRF guard. */
class ProxyServerTest {
    private val closers = mutableListOf<() -> Unit>()
    private val stats = Stats()

    @After fun cleanup() = closers.forEach { runCatching { it() } }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startProxy(
        creds: ProxyCredentials? = null,
        blockLocal: Boolean = false,
        dns: Dns = Dns.SYSTEM,
        openUpstreamSocket: () -> Socket = ::Socket,
    ): Int {
        val port = freePort()
        ProxyServer(
            port, creds, stats, blockLocalDestinations = blockLocal, dns = dns,
            openUpstreamSocket = openUpstreamSocket,
        ).also {
            it.start()
            closers += { it.stop() }
        }
        return port
    }

    /** Tiny origin: answers any HTTP request with "hello <request-line>", or echoes raw bytes after "ECHO\n". */
    private fun startOrigin(): Int {
        val ss = ServerSocket(0).also { closers += { it.close() } }
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    s.use {
                        val input = it.getInputStream()
                        val first = input.read()
                        if (first == 'E'.code) { // raw echo mode
                            val buf = ByteArray(1024)
                            val n = input.read(buf)
                            it.getOutputStream().write(byteArrayOf(first.toByte()) + buf.copyOf(n))
                        } else {
                            val sb = StringBuilder().append(first.toChar())
                            while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
                            val line = sb.lines().first()
                            val body = "hello $line"
                            it.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()
                            )
                        }
                    }
                }.start()
            }
        }.start()
        return ss.localPort
    }

    private fun rawHttp(proxyPort: Int, request: String): String =
        Socket("127.0.0.1", proxyPort).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    @Test fun forwardsPlainHttpAndRewritesRequestLine() {
        val origin = startOrigin(); val proxy = startProxy()
        val resp = rawHttp(proxy, "GET http://127.0.0.1:$origin/a/b?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n")
        assertTrue(resp, resp.startsWith("HTTP/1.1 200"))
        assertTrue(resp, resp.endsWith("hello GET /a/b?x=1 HTTP/1.1"))
    }

    @Test fun connectTunnelsRawBytes() {
        val origin = startOrigin(); val proxy = startProxy()
        Socket("127.0.0.1", proxy).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("CONNECT 127.0.0.1:$origin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val din = DataInputStream(s.getInputStream())
            val status = StringBuilder()
            while (!status.endsWith("\r\n\r\n")) status.append(din.read().toChar())
            assertTrue(status.toString(), status.startsWith("HTTP/1.1 200"))
            s.getOutputStream().write("ECHO-me".toByteArray())
            val back = ByteArray(7).also { din.readFully(it) }
            assertEquals("ECHO-me", String(back))
        }
    }

    @Test fun stopClosesTheClientAndOriginOfAnActiveTunnel() {
        ServerSocket(0).use { origin ->
            origin.soTimeout = 3000
            val port = freePort()
            val proxy = ProxyServer(port, null, stats, blockLocalDestinations = false)
            proxy.start()
            closers += { proxy.stop() }
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                val request = "CONNECT 127.0.0.1:${origin.localPort} HTTP/1.1\r\nHost: x\r\n\r\n"
                client.getOutputStream().write(request.toByteArray())
                val input = DataInputStream(client.getInputStream())
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) header.append(input.readUnsignedByte().toChar())
                assertTrue(header.toString(), header.startsWith("HTTP/1.1 200"))
                origin.accept().use { upstream ->
                    upstream.soTimeout = 3000
                    client.getOutputStream().write(42)
                    assertEquals(42, upstream.getInputStream().read())

                    proxy.stop()

                    assertEquals(-1, input.read())
                    assertEquals(-1, upstream.getInputStream().read())
                }
            }
        }
    }

    @Test fun socketFactoryFailureDoesNotFallBackToTheDefaultRoute() {
        val origin = startOrigin()
        val proxy = startProxy(openUpstreamSocket = { throw SocketException("Selected network disconnected") })
        val response = rawHttp(proxy, "GET http://127.0.0.1:$origin/ HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 502"))
    }

    @Test fun socks5ConnectWithoutAuth() {
        val origin = startOrigin(); val proxy = startProxy()
        Socket("127.0.0.1", proxy).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream(); val din = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0)); assertEquals(5, din.readUnsignedByte()); assertEquals(0, din.readUnsignedByte())
            out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (origin shr 8).toByte(), origin.toByte()))
            val reply = ByteArray(10).also { din.readFully(it) }
            assertEquals(0, reply[1].toInt())
            out.write("ECHO-socks".toByteArray())
            val back = ByteArray(10).also { din.readFully(it) }
            assertEquals("ECHO-socks", String(back))
        }
    }

    @Test fun socks5RejectsBadPasswordAndAcceptsGood() {
        val origin = startOrigin(); val proxy = startProxy(ProxyCredentials("victor", "s3cret"))
        fun attempt(pass: String): Int = Socket("127.0.0.1", proxy).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream(); val din = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 2)); din.readUnsignedByte(); assertEquals(2, din.readUnsignedByte())
            val u = "victor".toByteArray(); val p = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            din.readUnsignedByte(); din.readUnsignedByte()
        }
        assertEquals(1, attempt("wrong")) // non-zero = failure
        assertEquals(0, attempt("s3cret"))
        assertTrue(origin > 0)
    }

    @Test fun httpProxyDemands407ThenAcceptsBasicCredentials() {
        val origin = startOrigin(); val proxy = startProxy(ProxyCredentials("victor", "s3cret"))
        val req = "GET http://127.0.0.1:$origin/ HTTP/1.1\r\nHost: x\r\n"
        assertTrue(rawHttp(proxy, req + "\r\n").startsWith("HTTP/1.1 407"))
        val token = Base64.getEncoder().encodeToString("victor:s3cret".toByteArray())
        assertTrue(rawHttp(proxy, req + "Proxy-Authorization: Basic $token\r\n\r\n").startsWith("HTTP/1.1 200"))
        val bad = Base64.getEncoder().encodeToString("victor:nope".toByteArray())
        assertTrue(rawHttp(proxy, req + "Proxy-Authorization: Basic $bad\r\n\r\n").startsWith("HTTP/1.1 407"))
    }

    @Test fun defaultPolicyRefusesLoopbackDestinations() {
        val origin = startOrigin(); val proxy = startProxy(blockLocal = true)
        val resp = rawHttp(proxy, "CONNECT 127.0.0.1:$origin HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(resp, resp.startsWith("HTTP/1.1 403"))
    }

    @Test fun httpForwardUsesConfiguredDns() {
        val origin = startOrigin()
        val proxy = startProxy(dns = testOriginDns())
        val response = rawHttp(proxy, "GET http://origin.invalid:$origin/ HTTP/1.1\r\nHost: origin.invalid\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
    }

    @Test fun connectStillRefusesLoopbackReturnedByConfiguredDns() {
        val proxy = startProxy(blockLocal = true, dns = testOriginDns())
        val response = rawHttp(proxy, "CONNECT origin.invalid:443 HTTP/1.1\r\nHost: origin.invalid\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 403"))
    }

    @Test fun socks5PassesTheClientHostnameToConfiguredDns() {
        val origin = startOrigin()
        val proxy = startProxy(dns = testOriginDns())
        Socket("127.0.0.1", proxy).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            val host = "origin.invalid".toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((origin shr 8).toByte(), origin.toByte()))
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(0, reply[1].toInt())
            val probe = "ECHO-dns".toByteArray(Charsets.US_ASCII)
            out.write(probe)
            val echo = ByteArray(probe.size).also(input::readFully)
            assertEquals("ECHO-dns", echo.toString(Charsets.US_ASCII))
        }
    }

    @Test fun configuredDnsFailureDoesNotFallBackToSystemDns() {
        val origin = startOrigin()
        val unreachableDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        }
        val proxy = startProxy(dns = unreachableDns)
        val response = rawHttp(proxy, "GET http://localhost:$origin/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 502"))
    }

    private fun testOriginDns() = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != "origin.invalid") throw UnknownHostException(hostname)
            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
    }

    @Test fun statsCountTraffic() {
        val origin = startOrigin(); val proxy = startProxy()
        rawHttp(proxy, "GET http://127.0.0.1:$origin/ HTTP/1.1\r\nHost: x\r\n\r\n")
        Thread.sleep(200)
        val snap = stats.snapshot()
        assertTrue(snap.totalConnections >= 1); assertTrue(snap.bytesDown > 0); assertTrue(snap.bytesUp > 0)
    }

    @Test fun refusesToBindWhenPortTaken() {
        val ss = ServerSocket().also { it.bind(InetSocketAddress(0)); closers += { it.close() } }
        val failed = runCatching { ProxyServer(ss.localPort, null, stats).start() }.isFailure
        assertTrue(failed)
    }
}
