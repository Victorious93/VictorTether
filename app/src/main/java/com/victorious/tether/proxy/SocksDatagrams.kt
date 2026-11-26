package com.victorious.tether.proxy

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

internal class SocksAddressType : IOException()

internal fun DataInputStream.readSocksHost(): String = when (readUnsignedByte()) {
    1 -> InetAddress.getByAddress(ByteArray(4).also(::readFully)).hostAddress.orEmpty()
    3 -> ByteArray(readUnsignedByte()).also(::readFully).toString(Charsets.UTF_8)
    4 -> InetAddress.getByAddress(ByteArray(16).also(::readFully)).hostAddress.orEmpty()
    else -> throw SocksAddressType()
}

internal fun InetSocketAddress.socksAddress(): ByteArray {
    val raw = address.address
    return byteArrayOf(if (raw.size == 4) 1 else 4) + raw + byteArrayOf((port ushr 8).toByte(), port.toByte())
}

/** An RFC 1928 UDP association with separate client and upstream network bindings. */
internal class SocksDatagrams(
    private val downstream: DatagramSocket,
    private val upstream: DatagramSocket,
    private val clientAddress: InetAddress,
    clientPort: Int,
    private val resolve: (String) -> InetAddress?,
    private val forward: (Int, Boolean, (Int) -> Unit) -> Unit,
) {
    @Volatile private var peerPort = clientPort
    private val destinations = linkedMapOf<InetSocketAddress, Long>()

    fun upload() {
        val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
        while (!downstream.isClosed) {
            packet.length = packet.data.size
            downstream.receive(packet)
            if (packet.address != clientAddress || (peerPort != 0 && peerPort != packet.port)) continue
            val input = ByteArrayInputStream(packet.data, 0, packet.length)
            val data = DataInputStream(input)
            val target = try {
                if (data.readUnsignedShort() != 0 || data.readUnsignedByte() != 0) continue
                val host = data.readSocksHost()
                val port = data.readUnsignedShort()
                if (port == 0) continue
                val address = resolve(host) ?: continue
                InetSocketAddress(address, port)
            } catch (_: IOException) {
                continue // Ignore malformed, fragmented and unresolvable datagrams.
            }
            if (peerPort == 0) peerPort = packet.port
            val length = input.available()
            forward(length, true) {
                synchronized(destinations) {
                    destinations.remove(target)
                    if (destinations.size >= MAX_DESTINATIONS) destinations.remove(destinations.keys.first())
                    destinations[target] = System.nanoTime()
                }
                upstream.send(DatagramPacket(packet.data, packet.length - length, length, target))
            }
        }
    }

    fun download() {
        val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
        while (!upstream.isClosed) {
            packet.length = packet.data.size
            upstream.receive(packet)
            val sender = packet.socketAddress as InetSocketAddress
            val requestedAt = synchronized(destinations) { destinations[sender] } ?: continue
            val port = peerPort
            if (port == 0 || System.nanoTime() - requestedAt > DESTINATION_LIFETIME) continue
            val header = byteArrayOf(0, 0, 0) + sender.socksAddress()
            if (header.size + packet.length > MAX_PACKET) continue
            val payload = header + packet.data.copyOf(packet.length)
            forward(packet.length, false) {
                downstream.send(DatagramPacket(payload, payload.size, clientAddress, port))
            }
        }
    }

    private companion object {
        const val MAX_PACKET = 65_507
        const val MAX_DESTINATIONS = 64
        val DESTINATION_LIFETIME = TimeUnit.MINUTES.toNanos(5)
    }
}
