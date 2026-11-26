package com.victorious.tether

import com.victorious.tether.proxy.ClientPolicy
import com.victorious.tether.proxy.ProxyClients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyClientsTest {
    @Test fun transferQuotaIsSharedByBothDirectionsAndConcurrentConnections() {
        val clients = ProxyClients()
        val address = "192.168.49.2"
        val first = Closeable { }
        val second = Closeable { }
        val entry = checkNotNull(clients.accept(address, first))
        assertEquals(entry, clients.accept(address, second))
        clients.update(address, ClientPolicy(mebibytesPerSession = 1))

        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val firstSize = 1024 * 1024 - 9
            val firstWrite = workers.submit<Int> {
                entry.forward(first, firstSize, upload = true, partial = false) {
                    ready.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertEquals(9, entry.forward(second, 32, upload = false, partial = true) { assertEquals(9, it) })
            release.countDown()
            assertEquals(firstSize, firstWrite.get(3, TimeUnit.SECONDS))
            assertTrue(clients.snapshots().single().quotaReached)

            val recovered = ProxyClients().apply { restore(clients.snapshots()) }
            assertNull(recovered.accept(address, Closeable { }))
            assertEquals(0, recovered.snapshots().single().activeConnections)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun rateLimitPacesForwarding() {
        val clients = ProxyClients()
        val peer = Closeable { }
        val address = "192.168.49.2"
        val entry = checkNotNull(clients.accept(address, peer))
        val started = System.nanoTime()
        clients.update(address, ClientPolicy(kibibytesPerSecond = 4))
        assertEquals(1024, entry.forward(peer, 1024, upload = true, partial = false) { })
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Forwarded 1 KiB in $elapsed ms at a rate of 4 KiB/s", elapsed >= 200)
    }
}
