package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The health check the supervisor trusts to declare the runtime HEALTHY (Phase
 * 8 test-matrix item: "health checks"). Runs against a real localhost
 * [ServerSocket] stand-in, so the wire contract (method, path, Basic auth
 * header, status handling, "healthy" body requirement) is pinned on the JVM.
 * The device gates (H2/G6 lineage, P8 cold/warm-start) then prove the same code
 * against the real embedded server.
 *
 * Health is a verified HTTP response, never "process launched": 2xx AND a body
 * that actually reports `healthy`. Anything else - 401, 500, a 200 with the
 * wrong body, a refused socket - is unhealthy, and the waiter must stay bounded
 * (never block forever).
 */
class HealthCheckerTest {

    /** Minimal HTTP/1.1 responder that records the Authorization header. */
    private class Responder(
        private val behavior: (request: Int) -> Pair<Int, String>,
    ) : Thread("health-test-socket") {
        init { isDaemon = true }  // never hold the JVM test worker open
        @Volatile var port = -1
        @Volatile var lastAuthHeader: String? = null
        @Volatile var lastRequestLine: String? = null
        private val requests = AtomicInteger(0)
        private val started = CountDownLatch(1)
        private var socket: ServerSocket? = null

        override fun run() {
            val sock = ServerSocket()
            socket = sock
            sock.use {
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                port = sock.localPort
                started.countDown()
                while (!isInterrupted) {
                    val conn = runCatching { sock.accept() }.getOrNull() ?: break
                    Thread({
                        runCatching {
                            val inStream = conn.getInputStream()
                            // Read until the blank line, byte by byte: a
                            // BufferedReader would prefetch the (empty) body
                            // and hang - the same trap Phase 5's fake hit.
                            val header = StringBuilder()
                            var tail = ""
                            while (true) {
                                val b = inStream.read()
                                if (b < 0) break
                                header.append(b.toChar())
                                tail = (tail + b.toChar()).takeLast(4)
                                if (tail == "\r\n\r\n") break
                            }
                            val lines = header.toString().split("\r\n")
                            lastRequestLine = lines.firstOrNull()
                            lastAuthHeader = lines.firstOrNull { it.startsWith("Authorization:") }
                                ?.let { line -> line.substringAfter(':', "").trim() }
                            val n = requests.incrementAndGet()
                            val (status, body) = behavior(n)
                            val out = conn.getOutputStream()
                            val reason = when (status) {
                                200 -> "OK"
                                401 -> "Unauthorized"
                                else -> "Internal Server Error"
                            }
                            out.write(
                                ("HTTP/1.1 $status $reason\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                            out.write(body.toByteArray())
                            out.flush()
                        }
                        runCatching { conn.close() }
                    }).apply { isDaemon = true; start() }
                }
            }
        }

        fun awaitReady(): Boolean = started.await(5, TimeUnit.SECONDS)

        /** Deterministic shutdown: close the listening socket (interrupt alone
         * cannot unblock a plain accept()). Named so because Thread.stop() is a
         * final supertype member this class cannot hide. */
        fun shutdown() {
            interrupt()
            runCatching { socket?.close() }
        }
    }

    private fun newProbe(behavior: (Int) -> Pair<Int, String>): Pair<HealthChecker, Responder> {
        val r = Responder(behavior)
        r.start()
        assertTrue("test socket did not start", r.awaitReady())
        return HealthChecker(port = r.port) to r
    }

    @Test
    fun aHealthyTwoHundredIsReportedHealthy() {
        val (probe, r) = newProbe { 200 to """{"healthy":true,"version":"1.18.23-test"}""" }
        try {
            val h = probe.probe("pw")
            assertTrue("must be healthy: $h", h.healthy)
            assertEquals(200, h.code)
            assertTrue(h.body.contains("healthy"))
            // The request shape the loopback server expects: GET /global/health
            // with the Basic auth header the server verifies.
            assertEquals("GET /global/health HTTP/1.1", r.lastRequestLine)
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("${RuntimeEnv.SERVER_USER}:pw".toByteArray()),
                r.lastAuthHeader,
            )
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun aWrongPasswordIsUnhealthyWithTheServersStatus() {
        val (probe, r) = newProbe { 401 to """{"error":"unauthorized"}""" }
        try {
            val h = probe.probe("wrong-password")
            assertFalse(h.healthy)
            assertEquals(401, h.code)
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun aTwoHundredThatDoesNotReportHealthyIsNotHealthy() {
        // The server's body is the authority: a 200 that does not say
        // "healthy" is a different endpoint (or a broken one) and must not
        // flip the supervisor to HEALTHY.
        val (probe, r) = newProbe { 200 to """{"status":"weird"}""" }
        try {
            val h = probe.probe("pw")
            assertEquals(200, h.code)
            assertFalse("a 200 without a healthy body must not be healthy", h.healthy)
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun aRefusedSocketIsUnhealthyNotAnException() {
        // No listener: the probe must report, not throw - the supervisor calls
        // this in a loop and treats every result as data.
        val probe = HealthChecker(port = 1) // port 1: nothing listens, connect refused
        val h = probe.probe("pw", timeoutMs = 2000)
        assertFalse(h.healthy)
        assertEquals(-1, h.code)
        assertNotNull("the failure must carry a reason", h.body)
    }

    @Test
    fun waitHealthyReturnsOnTheFirstHealthyProbe() {
        var seen = 0
        val (probe, r) = newProbe { n ->
            seen = n
            if (n < 3) 500 to "starting" else 200 to """{"healthy":true,"version":"v"}"""
        }
        try {
            val start = System.currentTimeMillis()
            val h = probe.waitHealthy("pw", timeoutMs = 30_000, intervalMs = 50)
            val elapsed = System.currentTimeMillis() - start
            assertNotNull(h)
            assertTrue("must be the healthy probe: $h", h!!.healthy)
            assertEquals("the third probe must be the one that returned", 3, seen)
            assertTrue("must not return before the third attempt", elapsed >= 100)
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun waitHealthyIsBoundedAndReturnsTheLastObservation() {
        val (probe, r) = newProbe { 500 to "still not up" }
        try {
            val start = System.currentTimeMillis()
            val h = probe.waitHealthy("pw", timeoutMs = 600, intervalMs = 100)
            val elapsed = System.currentTimeMillis() - start
            // Bounded: returned in a second or two, not forever.
            assertTrue("waitHealthy must be bounded, took ${elapsed}ms", elapsed < 10_000)
            // And it reports what it last saw instead of pretending.
            assertNotNull("last observation must be returned, not dropped", h)
            assertFalse(h!!.healthy)
            assertEquals(500, h.code)
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun waitHealthyTimesOutCleanlyWhenNothingAnswers() {
        val probe = HealthChecker(port = 1)
        val start = System.currentTimeMillis()
        val h = probe.waitHealthy("pw", timeoutMs = 500, intervalMs = 100)
        val elapsed = System.currentTimeMillis() - start
        // Even with no listener the waiter must stop, and it must say so.
        assertTrue("bounded: ${elapsed}ms", elapsed < 10_000)
        if (h != null) assertFalse(h.healthy)
    }
}
