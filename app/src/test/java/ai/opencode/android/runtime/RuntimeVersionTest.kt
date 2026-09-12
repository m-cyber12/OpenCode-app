package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version handling (Phase 8 test-matrix item: "version handling").
 *
 * The app must never execute a payload whose pins do not match the app it was
 * built against: `RuntimeVersion.validateManifest` is the gate the extractor
 * consults, and a mismatch means "the APK payload and the app disagree" - a
 * state where re-extracting would only install the same incompatible artifact
 * forever. So every one of the six pins is checked, and this test pins that
 * each field independently trips the gate (a future change to one pin must
 * keep the other five enforced).
 */
class RuntimeVersionTest {

    /** A manifest matching every pin in [RuntimeVersion] (i.e. a valid one). */
    private fun validManifest(): RuntimeManifest = RuntimeManifest(
        payloadVersion = RuntimeVersion.PAYLOAD_VERSION,
        opencodeCommit = RuntimeVersion.OPENCODE_COMMIT,
        opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
        bunVersion = RuntimeVersion.BUN_VERSION,
        gitVersion = RuntimeVersion.GIT_VERSION,
        rgVersion = RuntimeVersion.RIPGREP_VERSION,
        payloadSha256 = "deadbeef",
        entries = listOf(
            ManifestEntry("launcher.js", "a".repeat(64), 123),
            ManifestEntry("opencode/dist/node/node.js", "b".repeat(64), 456),
        ),
    )

    @Test
    fun aMatchingManifestIsValid() {
        assertNull(RuntimeVersion.validateManifest(validManifest()))
    }

    @Test
    fun aWrongPayloadVersionIsRejected() {
        val m = validManifest().copy(payloadVersion = RuntimeVersion.PAYLOAD_VERSION + 1)
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("payloadVersion"))
    }

    @Test
    fun aWrongOpencodeCommitIsRejected() {
        val m = validManifest().copy(opencodeCommit = "0000000000000000000000000000000000000000")
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("opencodeCommit"))
    }

    @Test
    fun aWrongOpencodeVersionIsRejected() {
        val m = validManifest().copy(opencodeVersion = "9.9.9")
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("opencodeVersion"))
    }

    @Test
    fun aWrongBunVersionIsRejected() {
        val m = validManifest().copy(bunVersion = "0.1.0")
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("bunVersion"))
    }

    @Test
    fun aWrongGitVersionIsRejected() {
        val m = validManifest().copy(gitVersion = "v1.0.0")
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("gitVersion"))
    }

    @Test
    fun aWrongRipgrepVersionIsRejected() {
        val m = validManifest().copy(rgVersion = "9.9.9")
        val reason = RuntimeVersion.validateManifest(m)
        assertNotNull(reason)
        assertTrue("reason must name the field: $reason", reason!!.contains("rgVersion"))
    }

    @Test
    fun everyMismatchIsReportedTogether() {
        val m = validManifest().copy(
            bunVersion = "x",
            gitVersion = "y",
            rgVersion = "z",
        )
        val reason = RuntimeVersion.validateManifest(m)!!
        assertTrue(reason.contains("bunVersion"))
        assertTrue(reason.contains("gitVersion"))
        assertTrue(reason.contains("rgVersion"))
        // The three matching fields must not be blamed.
        assertTrue(!reason.contains("opencodeCommit="))
        assertTrue(!reason.contains("opencodeVersion="))
    }

    @Test
    fun thePinsMatchTheLockfileContract() {
        // The pins this app enforces - sanity-checked here so a copy/paste
        // regression in one of the six constants is caught by the unit suite,
        // not only by a device run that re-extracts forever.
        assertEquals("05ea5073be967c779d326929b2de6228dda4159d", RuntimeVersion.OPENCODE_COMMIT)
        assertEquals(40, RuntimeVersion.OPENCODE_COMMIT.length)
        assertEquals("1.18.23", RuntimeVersion.OPENCODE_VERSION)
        assertEquals("1.3.14", RuntimeVersion.BUN_VERSION)
        assertEquals("v2.48.1", RuntimeVersion.GIT_VERSION)
        assertEquals("15.1.0", RuntimeVersion.RIPGREP_VERSION)
        // The payload version is what the extraction marker stamps; Phase 5's
        // P5-02 gate asserts the installed APK carries it.
        assertTrue(RuntimeVersion.PAYLOAD_VERSION >= 5)
    }

    // ---- manifest paths (checksum-verification input) -----------------------

    @Test
    fun safeRelativePathsAreAccepted() {
        for (p in listOf("launcher.js", "opencode/dist/node/node.js", "node_modules/jsonc-parser/index.js", "a/b/c")) {
            assertTrue("must be safe: $p", RuntimeManifest.isSafeRelativePath(p))
        }
    }

    @Test
    fun unsafePathsAreRejected() {
        for (p in listOf(
            "",                       // empty
            "/etc/passwd",            // absolute
            "../escape",              // parent from the root
            "a/../../escape",         // parent via a segment
            "a//b",                   // empty segment
            "a/./b",                  // explicit dot segment
            "a\\b",                   // windows separator (normalised, this one is safe - see below)
        )) {
            if (p == "a\\b") {
                // Backslashes are normalised to '/' before the segment check,
                // so "a\b" is treated as the relative path a/b - accepted.
                assertTrue(RuntimeManifest.isSafeRelativePath(p))
                continue
            }
            assertTrue("must be unsafe: $p", !RuntimeManifest.isSafeRelativePath(p))
        }
        // A NUL byte is never a path.
        assertTrue(!RuntimeManifest.isSafeRelativePath("a\u0000b"))
        // Absolute via backslash.
        assertTrue(!RuntimeManifest.isSafeRelativePath("\\etc\\passwd"))
    }

    @Test
    fun aManifestWithAnUnsafePathIsRefusedOnParse() {
        val json = """
            {"payloadVersion":1,"opencodeCommit":"c","opencodeVersion":"v","bunVersion":"b",
             "gitVersion":"g","rgVersion":"r","files":{"../evil":{"sha256":"x","size":1}}}
        """.trimIndent()
        try {
            RuntimeManifest.fromJson(json)
            throw AssertionError("unsafe manifest path must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
    }

    @Test
    fun aManifestWithoutFilesIsRefusedOnParse() {
        val json = """
            {"payloadVersion":1,"opencodeCommit":"c","opencodeVersion":"v","bunVersion":"b",
             "gitVersion":"g","rgVersion":"r","files":{}}
        """.trimIndent()
        try {
            RuntimeManifest.fromJson(json)
            throw AssertionError("an empty manifest must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no payload files"))
        }
    }

    @Test
    fun manifestJsonRoundTrips() {
        val m = validManifest()
        val parsed = RuntimeManifest.fromJson(m.toJson().toString())
        assertEquals(m.payloadVersion, parsed.payloadVersion)
        assertEquals(m.opencodeCommit, parsed.opencodeCommit)
        assertEquals(m.entries.size, parsed.entries.size)
        assertEquals(m.entries[0].path, parsed.entries[0].path)
        assertEquals(m.entries[0].sha256, parsed.entries[0].sha256)
        assertEquals(m.entries[0].size, parsed.entries[0].size)
        // A round trip must still validate against the pins.
        assertNull(RuntimeVersion.validateManifest(parsed))
    }
}
