package com.victorious.tether.proxy

import android.net.InetAddresses
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.Closeable
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Resolves proxy destinations through a user-selected HTTPS resolver. */
internal class HttpsProxyDns(endpoint: HttpUrl) : Dns, Closeable {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            try {
                // Bound chunked responses as well as responses with a Content-Length header.
                if (response.isSuccessful && response.body?.source()?.request(65_536) == true) {
                    throw IOException("DNS response exceeds the maximum DNS message size")
                }
                response
            } catch (error: IOException) {
                response.close()
                throw error
            }
        }
        .build()

    private val resolver = DnsOverHttps.Builder()
        .client(client)
        .url(endpoint)
        .post(true)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.removeSurrounding("[", "]")
        if (InetAddresses.isNumericAddress(host)) {
            return listOf(InetAddresses.parseNumericAddress(host))
        }
        val dnsName = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).removeSuffix(".")
        } catch (error: IllegalArgumentException) {
            throw UnknownHostException("Invalid proxy hostname").apply { initCause(error) }
        }
        if (dnsName.isEmpty() || dnsName.length > 253) throw UnknownHostException("Invalid proxy hostname")
        return try {
            // Keep dual-stack responses deterministic when the proxy connects to one address.
            resolver.lookup(dnsName).sortedBy { it.address.size }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException("DNS lookup interrupted").apply { initCause(error) }
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}
