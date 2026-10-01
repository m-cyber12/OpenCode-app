package ai.opencode.android.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * v9.9: the loopback port watcher that turns ANY agent-started server into a
 * preview the user can open. Real sockets, no mocks - the scanner's whole job
 * is talking to a kernel.
 */
class PortScannerTest {

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun findsAListeningPortAndSkipsClosedOnes() {
        ServerSocket(0, 4, loopback).use { listening ->
            val open = listening.localPort
            val closed = ServerSocket(0, 4, loopback).let { probe ->
                val p = probe.localPort
                probe.close()
                p
            }
            val scanner = PortScanner()
            assertEquals(listOf(open), scanner.scan(listOf(closed, open)))
        }
    }

    @Test
    fun aClosedPortStopsBeingReported() {
        val scanner = PortScanner()
        val port: Int
        ServerSocket(0, 4, loopback).use { listening ->
            port = listening.localPort
            assertTrue(scanner.isOpen(port))
        }
        assertFalse(scanner.isOpen(port))
    }

    @Test
    fun scanPreservesCandidateOrderForTheDoor() {
        ServerSocket(0, 4, loopback).use { a ->
            ServerSocket(0, 4, loopback).use { b ->
                val scanner = PortScanner()
                val found = scanner.scan(listOf(b.localPort, a.localPort))
                assertEquals(listOf(b.localPort, a.localPort), found)
            }
        }
    }

    @Test
    fun theAppsOwnStaticPortLeadsTheWatchedList() {
        // 8642 (PreviewServer's preferred port) must stay watched so a pane
        // the user closed can be reopened from the door.
        assertEquals(8642, PortScanner.CANDIDATE_PORTS.first())
        assertTrue(PortScanner.CANDIDATE_PORTS.contains(8080))
    }
}
