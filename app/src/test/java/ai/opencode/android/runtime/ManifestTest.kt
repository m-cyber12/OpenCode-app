package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {

    private val json = """
    {
      "payloadVersion": 4,
      "opencodeCommit": "05ea5073be967c779d326929b2de6228dda4159d",
      "opencodeVersion": "1.18.23",
      "bunVersion": "1.3.14",
      "gitVersion": "v2.48.1",
      "rgVersion": "15.1.0",
      "payloadSha256": "abc",
      "files": {
        "opencode/dist/node/node.js": { "sha256": "aaa", "size": 12345 },
        "launcher.js": { "sha256": "bbb", "size": 42 }
      }
    }
    """.trimIndent()

    @Test
    fun roundTripsVersionsAndEntries() {
        val m = RuntimeManifest.fromJson(json)
        assertEquals(4, m.payloadVersion)
        assertEquals("1.18.23", m.opencodeVersion)
        assertEquals("1.3.14", m.bunVersion)
        assertEquals(2, m.entries.size)
        val node = m.entries.first { it.path.endsWith("node.js") }
        assertEquals(12345L, node.size)
        assertEquals("aaa", node.sha256)
        assertEquals(null, RuntimeVersion.validateManifest(m))
        // round-trip via toJson
        val m2 = RuntimeManifest.fromJson(m.toJson().toString())
        assertEquals(m.entries.size, m2.entries.size)
        assertEquals(m.opencodeCommit, m2.opencodeCommit)
    }

    @Test
    fun unknownFieldsTolerated() {
        // forward compatibility: extra keys must not break parsing
        val withExtra = json.replace("\"payloadSha256\": \"abc\",", "\"payloadSha256\": \"abc\",\n\"future\": 99,")
        val m = RuntimeManifest.fromJson(withExtra)
        assertEquals("15.1.0", m.rgVersion)
        assertTrue(m.entries.isNotEmpty())
    }

    @Test
    fun rejectsManifestVersionDriftFromLock() {
        val m = RuntimeManifest.fromJson(json)
        val problem = RuntimeVersion.validateManifest(m.copy(bunVersion = "9.9.9"))
        assertTrue(problem!!.contains("bunVersion"))
    }

    @Test
    fun rejectsUnsafeManifestPaths() {
        assertTrue(RuntimeManifest.isSafeRelativePath("opencode/dist/node/node.js"))
        assertTrue(!RuntimeManifest.isSafeRelativePath("../outside"))
        assertTrue(!RuntimeManifest.isSafeRelativePath("/absolute"))
        assertTrue(!RuntimeManifest.isSafeRelativePath("opencode/../outside"))
    }
}
