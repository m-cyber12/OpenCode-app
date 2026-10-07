package ai.opencode.android.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v9.24: the pure half of Add-ons. What is pinned here is the contract the
 * BRIEF teaches agents (`<workspace>/.addons/next-swc-wasm-<version>.tgz`) -
 * if a rename drifted, every brief instruction would silently point at
 * nothing, and the owner would only find out through a wasted device run.
 */
class AddonsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun catalogCarriesTheWasmCompilerUnderTheBriefedName() {
        val spec = Addons.CATALOG.single()
        assertEquals("next-swc-wasm", spec.id)
        assertEquals("@next/swc-wasm-nodejs", spec.npmPackage)
        assertEquals(".addons", Addons.DIR_NAME)
    }

    @Test
    fun manifestUrlEscapesTheScopedSlashOnly() {
        assertEquals(
            "https://registry.example/@next%2Fswc-wasm-nodejs/latest",
            Addons.manifestUrl("https://registry.example/", "@next/swc-wasm-nodejs", "latest"),
        )
        assertEquals(
            "https://registry.example/@next%2Fswc-wasm-nodejs/15.5.3",
            Addons.manifestUrl("https://registry.example", "@next/swc-wasm-nodejs", "15.5.3"),
        )
    }

    @Test
    fun manifestParsingReadsVersionTarballAndSize() {
        val m = Addons.parseManifest(
            """{"name":"@next/swc-wasm-nodejs","version":"15.5.3",
               "dist":{"tarball":"https://registry.example/x.tgz","unpackedSize":573000000}}""",
        )
        assertEquals("15.5.3", m.version)
        assertEquals("https://registry.example/x.tgz", m.tarballUrl)
        assertEquals(573000000L, m.unpackedSize)
        // Size is optional on older registry entries.
        val noSize = Addons.parseManifest(
            """{"version":"1.0.0","dist":{"tarball":"https://registry.example/y.tgz"}}""",
        )
        assertEquals(0L, noSize.unpackedSize)
    }

    @Test
    fun versionSafetyRejectsEverythingThatCouldEscapeTheAddonsDir() {
        assertTrue(Addons.isSafeVersion("15.5.3"))
        assertTrue(Addons.isSafeVersion("15.0.0-canary.17"))
        assertTrue(Addons.isSafeVersion("1.0.0+build.5"))
        assertFalse(Addons.isSafeVersion(""))
        assertFalse(Addons.isSafeVersion("../../../etc"))
        assertFalse(Addons.isSafeVersion("1.0/evil"))
        assertFalse(Addons.isSafeVersion("1.0 evil"))
        assertFalse(Addons.isSafeVersion("x".repeat(65)))
    }

    @Test
    fun tarballNameIsExactlyWhatTheBriefTeaches() {
        assertEquals(
            "next-swc-wasm-15.5.3.tgz",
            Addons.tarballName(Addons.CATALOG.single(), "15.5.3"),
        )
    }

    @Test
    fun installedListsFinishedTarballsOnlySorted() {
        val root = tmp.newFolder("workspace")
        val dir = File(root, ".addons").apply { mkdirs() }
        File(dir, "next-swc-wasm-15.5.3.tgz").writeText("b")
        File(dir, "next-swc-wasm-14.2.0.tgz").writeText("a")
        File(dir, "next-swc-wasm-16.0.0.tgz.part").writeText("crashed download")
        File(dir, "notes.txt").writeText("not an addon")
        File(dir, "subdir.tgz").mkdirs() // a directory never counts
        assertEquals(
            listOf("next-swc-wasm-14.2.0.tgz", "next-swc-wasm-15.5.3.tgz"),
            Addons.installed(root).map { it.name },
        )
        // And a workspace with no .addons dir is simply empty, never a crash.
        assertEquals(emptyList<File>(), Addons.installed(tmp.newFolder("empty")))
    }
}
