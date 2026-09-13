package ai.opencode.android.runtime

import ai.opencode.android.client.LoopbackGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Environment construction for the embedded server (Phase 8 test-matrix items:
 * "environment construction" and "port selection").
 *
 * [RuntimeEnv.build] is the only place the child's environment is assembled, so
 * pinning it on the JVM pins three security properties the device gates also
 * assert:
 *   1. every writable path the server uses is app-private (HOME, the XDG_*
 *      variables, TMPDIR - all under filesDir); nothing in shared storage;
 *   2. the server is told to bind to a loopback literal only, and a hostile
 *      override is refused, never honoured;
 *   3. the port and user are fixed constants (the supervisor's duplicate
 *      prevention relies on exactly one port: the bind itself is the final
 *      guard, proven on device by G13/H3 lineage).
 */
class RuntimeEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(): RuntimePaths {
        val files = tmp.newFolder("files")
        val native = tmp.newFolder("native")
        return RuntimePaths.forTesting(files, native)
    }

    @Test
    fun buildPointsEveryWritablePathAtAppPrivateStorage() {
        val p = paths()
        val env = RuntimeEnv.build(p, "arm64-v8a", "password-123")

        for (key in listOf("HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME", "TMPDIR")) {
            val v = env[key]
            assertNotNull("missing $key", v)
            assertTrue("$key must be under filesDir (got $v)", v!!.startsWith(p.filesDir.absolutePath))
            // And outside the public/shared tree the server must never touch.
            assertFalse("$key must not be a system path (got $v)", v.startsWith("/system/"))
        }
        assertEquals(p.home.absolutePath, env["HOME"])
        assertEquals(p.xdgData.absolutePath, env["XDG_DATA_HOME"])
        assertEquals(p.xdgConfig.absolutePath, env["XDG_CONFIG_HOME"])
    }

    @Test
    fun buildPutstheBundledToolsFirstOnThePath() {
        val p = paths()
        val env = RuntimeEnv.build(p, "x86_64", "password-123")
        val path = env["PATH"]!!
        val entries = path.split(File.pathSeparator)
        assertEquals(p.binDir.absolutePath, entries.first())
        assertTrue(entries.contains("/system/bin"))
        assertTrue(entries.contains("/system/xbin"))
        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals("C.UTF-8", env["LANG"])
    }

    @Test
    fun buildCarriesTheServerAuthAndIdentity() {
        val p = paths()
        val env = RuntimeEnv.build(p, "arm64-v8a", "the-loopback-password")

        assertEquals(RuntimeEnv.SERVER_USER, env["OPENCODE_SERVER_USERNAME"])
        assertEquals("the-loopback-password", env["OPENCODE_SERVER_PASSWORD"])
        assertEquals(RuntimeEnv.SERVER_PORT.toString(), env["OPENCODE_SERVER_PORT"])
        assertEquals(LoopbackGuard.SERVER_BIND_HOSTNAME, env["OPENCODE_SERVER_HOSTNAME"])
        assertEquals("android", env["OPENCODE_CLIENT"])
        assertEquals("arm64-v8a", env["OPENCODE_RUNTIME_ABI"])

        // The launcher glue paths must point at the flat, validated payload.
        assertEquals(p.filesDir.absolutePath, env["OPENCODE_FILES_DIR"])
        assertEquals(p.serverBundle.absolutePath, env["OPENCODE_BUNDLE"])
        assertEquals(File(p.nativeLibraryDir, "libseccompshim.so").absolutePath, env["OPENCODE_SECCOMP_SHIM"])
    }

    @Test
    fun buildHonoursALoopbackHostnameOverrideButNothingElse() {
        val p = paths()
        assertEquals("127.0.0.1", RuntimeEnv.build(p, "arm64-v8a", "pw", hostname = "127.0.0.1")["OPENCODE_SERVER_HOSTNAME"])
        // localhost/::1 are loopback literals, so they are honoured verbatim.
        assertEquals("localhost", RuntimeEnv.build(p, "arm64-v8a", "pw", hostname = "localhost")["OPENCODE_SERVER_HOSTNAME"])
        assertEquals("::1", RuntimeEnv.build(p, "arm64-v8a", "pw", hostname = "::1")["OPENCODE_SERVER_HOSTNAME"])
    }

    @Test
    fun hostnameIsFailClosedForEveryNonLoopbackRequest() {
        // null/empty -> the default, no refusal (there was no request).
        val none = RuntimeEnv.hostname(null)
        assertEquals(LoopbackGuard.SERVER_BIND_HOSTNAME, none.first)
        assertNull(none.second)
        val empty = RuntimeEnv.hostname("   ")
        assertEquals(LoopbackGuard.SERVER_BIND_HOSTNAME, empty.first)
        assertNull(empty.second)

        // Every non-loopback request is refused and the default is used.
        for (host in listOf("0.0.0.0", "::", "10.0.2.2", "192.168.1.50", "172.16.0.1", "mydevice.local", "8.8.8.8")) {
            val (bound, refused) = RuntimeEnv.hostname(host)
            assertEquals("non-loopback request '$host' must be replaced by the default", LoopbackGuard.SERVER_BIND_HOSTNAME, bound)
            assertNotNull("non-loopback request '$host' must be reported as refused", refused)
        }
    }

    @Test
    fun hostnameAcceptsTheFullLoopbackSet() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "ip6-localhost", "LOCALHOST", "127.0.0.1 ")) {
            val (bound, refused) = RuntimeEnv.hostname(host)
            assertEquals("loopback request '$host' must be honoured", host.trim(), bound)
            assertNull("loopback request '$host' must not be refused", refused)
        }
    }

    @Test
    fun portAndUserAreFixedAndValid() {
        // The single fixed port is the app's duplicate-prevention contract:
        // two supervisors would race for it, and the loser's bind fails.
        assertEquals(4111, RuntimeEnv.SERVER_PORT)
        assertTrue("port must be a valid TCP port", RuntimeEnv.SERVER_PORT in 1..65535)
        assertTrue("port must be an ephemeral-avoiding fixed value, not 0", RuntimeEnv.SERVER_PORT != 0)
        assertEquals("opencode", RuntimeEnv.SERVER_USER)
        assertTrue("bind hostname must be a loopback literal", LoopbackGuard.isLoopbackLiteral(LoopbackGuard.SERVER_BIND_HOSTNAME))
    }
}
