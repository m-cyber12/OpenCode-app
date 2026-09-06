package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback-only policy is a security boundary, so every branch of it is
 * pinned here: what the server may bind to, and what the client may dial.
 */
class LoopbackGuardTest {

    @Test
    fun bindHostname_defaultsToLoopbackWhenUnset() {
        val (host, refused) = LoopbackGuard.bindHostname(null)
        assertEquals("127.0.0.1", host)
        assertEquals(null, refused)
    }

    @Test
    fun bindHostname_acceptsLoopbackForms() {
        for (h in listOf("127.0.0.1", "localhost", "::1", " 127.0.0.1 ")) {
            val (host, refused) = LoopbackGuard.bindHostname(h)
            assertEquals(h.trim(), host)
            assertEquals(null, refused)
        }
    }

    @Test
    fun bindHostname_refusesNonLoopbackAndKeepsLoopback() {
        for (h in listOf("0.0.0.0", "10.0.2.15", "192.168.1.5", "opencode.example.com", "::")) {
            val (host, refused) = LoopbackGuard.bindHostname(h)
            assertEquals("127.0.0.1", host)
            assertTrue("$h must be refused", refused != null && refused.contains("loopback-only"))
        }
    }

    @Test
    fun checked_acceptsLoopbackUrlsOnly() {
        assertEquals("http://127.0.0.1:4111", LoopbackGuard.checked("http://127.0.0.1:4111/"))
        assertEquals("http://localhost:4111", LoopbackGuard.checked("http://localhost:4111"))
        for (bad in listOf(
            "", "http://10.0.2.2:4111", "https://api.openrouter.ai", "http://[::ffff:10.0.0.5]:4111",
            "ftp://127.0.0.1", "http://127.0.0.1.evil.example",
        )) {
            val thrown = runCatching { LoopbackGuard.checked(bad).let { null } }.exceptionOrNull()
            assertTrue("'$bad' must be rejected, got $thrown", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun isLoopbackLiteral_coversThe127Block() {
        assertTrue(LoopbackGuard.isLoopbackLiteral("127.0.0.1"))
        assertTrue(LoopbackGuard.isLoopbackLiteral("127.3.4.5"))
        assertTrue(LoopbackGuard.isLoopbackLiteral("[::1]"))
        assertFalse(LoopbackGuard.isLoopbackLiteral("0.0.0.0"))
        assertFalse(LoopbackGuard.isLoopbackLiteral("10.0.2.15"))
        assertFalse(LoopbackGuard.isLoopbackLiteral(""))
    }
}
