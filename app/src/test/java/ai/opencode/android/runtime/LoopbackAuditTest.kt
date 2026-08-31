package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel's /proc/net/* encoding is byte order specific, so the parser that
 * turns it into a verdict is tested against real-shaped rows here (the on-device
 * gate proves the rows exist; this proves we read them correctly).
 */
class LoopbackAuditTest {

    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    @Test
    fun formatsIpv4LittleEndianWord() {
        assertEquals("127.0.0.1", LoopbackAudit.formatAddress("0100007F", false))
        assertEquals("0.0.0.0", LoopbackAudit.formatAddress("00000000", false))
        assertEquals("10.0.2.15", LoopbackAudit.formatAddress("0F02000A", false))
    }

    @Test
    fun formatsIpv6LoopbackAndWildcard() {
        assertEquals("::1", LoopbackAudit.formatAddress("00000000000000000000000001000000", true))
        assertEquals("::", LoopbackAudit.formatAddress("00000000000000000000000000000000", true))
        assertEquals(
            "::ffff:127.0.0.1",
            LoopbackAudit.formatAddress("0000000000000000FFFF00000100007F", true),
        )
    }

    @Test
    fun parsesLoopbackListenRow() {
        val rows = LoopbackAudit.parseTcpLines(
            listOf(
                "   0: 0100007F:100F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10182        0 53321 1 0000000000000000 100 0 0 10 0",
            ),
            "tcp",
        )
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals("127.0.0.1", r.localAddress)
        assertEquals(4111, r.localPort)
        assertEquals("0A", r.state)
        assertEquals(10182, r.uid)
        assertTrue(r.listening)
        assertTrue(r.loopback)
        assertFalse(r.wildcard)
    }

    @Test
    fun distinguishesWildcardListenRow() {
        val rows = LoopbackAudit.parseTcpLines(
            listOf(
                "   0: 00000000:100F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10182        0 1 1 0",
                "   1: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10182        0 2 1 0",
                "   2: 0100007F:100F 0B02000A:C9A4 01 00000000:00000000 00:00000000 00000000  10182        0 3 1 0",
            ),
            "tcp",
        )
        assertEquals(3, rows.size)
        // Only the two 0A rows are listeners; the wildcard one is the violation.
        val listeners = rows.filter { it.listening }
        assertEquals(2, listeners.size)
        assertTrue(listeners.first { it.localPort == 4111 }.wildcard)
        assertTrue(listeners.first { it.localPort == 8080 }.loopback)
        // An ESTABLISHED row on the same port is not a listener.
        assertFalse(rows.first { it.state == "01" }.listening)
    }

    @Test
    fun skipsHeaderAndJunk() {
        val rows = LoopbackAudit.parseTcpLines(listOf(header, "", "garbage line here"), "tcp")
        assertTrue(rows.isEmpty())
    }

    @Test
    fun udpRowsCarryPortForMdnsDetection() {
        val rows = LoopbackAudit.parseTcpLines(
            listOf("   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  10182        0 9 1 0"),
            "udp",
        )
        assertEquals(1, rows.size)
        assertEquals(5353, rows[0].localPort)
        assertEquals("10182".toInt(), rows[0].uid)
    }
}
