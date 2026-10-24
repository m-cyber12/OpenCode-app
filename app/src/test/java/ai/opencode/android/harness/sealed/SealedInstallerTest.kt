package ai.opencode.android.harness.sealed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The install and the uninstall, end to end on a real filesystem.
 *
 * This is the mechanism the harness stands on, so the tests are about the
 * properties that make it publishable rather than about the code: after an
 * install every executable resolves into the APK's native library directory,
 * nothing that was extracted is executable, and an uninstall reclaims exactly
 * what it laid down and leaves the APK's own files alone.
 */
class SealedInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nativeDir: File by lazy { tmp.newFolder("native") }

    private val data = listOf(
        "etc/profile" to "export PATH=/usr/bin\n",
        "share/motd" to "welcome\n"
    )

    private fun elf(name: String): File {
        val file = File(nativeDir, name)
        file.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02, 0x01))
        file.setExecutable(true)
        return file
    }

    private fun payloadZip(entries: List<Pair<String, String>> = data): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun manifestLines(entries: List<Pair<String, String>> = data): List<String> =
        entries.map { (name, content) ->
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            RuntimeManifest.digest(bytes) + "\t" + bytes.size + "\t" + name
        }

    private fun request(prefix: File, execManifest: List<String> = listOf("bin/git\tlibgit.so")): SealedInstaller.Request =
        SealedInstaller.Request().apply {
            this.prefix = prefix
            this.nativeLibraryDirectory = nativeDir
            this.payload = ByteArrayInputStream(payloadZip())
            this.manifest = ByteArrayInputStream(manifestLines().joinToString("\n").toByteArray())
            this.execManifest = execManifest
        }

    @Test
    fun install_sealsTheExecutablesAndExtractsTheData() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")

        val result = SealedInstaller.install(request(prefix))

        assertTrue(result.summary(), result.ok())
        assertEquals(2, result.files)
        assertEquals(2, result.verified)
        assertEquals(1, result.executables)
        assertEquals("export PATH=/usr/bin\n", File(prefix, "etc/profile").readText())

        val git = File(prefix, "bin/git")
        assertTrue(Files.isSymbolicLink(git.toPath()))
        assertEquals(File(nativeDir, "libgit.so").absolutePath, SealedLinks.readLink(git))
    }

    @Test
    fun install_isIdempotent() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")

        val first = SealedInstaller.install(request(prefix))
        val second = SealedInstaller.install(request(prefix))

        assertEquals(1, first.executables)
        assertEquals(0, second.executables)
        assertEquals(1, second.alreadySealed)
        assertTrue(second.summary(), second.ok())
    }

    @Test
    fun install_reportsAMissingLibraryRatherThanShippingAnUnsealedPrefix() {
        val prefix = tmp.newFolder("prefix")

        val result = SealedInstaller.install(request(prefix))

        assertFalse(result.ok())
        assertTrue(result.summary(), result.problems.any { it.contains("libgit.so") })
    }

    @Test
    fun install_createsDataLinksBetweenExtractedFiles() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")
        val request = request(prefix).apply {
            linkManifest = listOf("lib/libssl.so\tlib/libssl.so.3")
            payload = ByteArrayInputStream(payloadZip(data + ("lib/libssl.so.3" to "shared object\n")))
        }

        val result = SealedInstaller.install(request)

        assertTrue(result.summary(), result.ok())
        assertEquals(1, result.links)
        assertTrue(Files.isSymbolicLink(File(prefix, "lib/libssl.so").toPath()))
    }

    @Test
    fun install_rejectsAnUnsafeLinkLine() {
        val prefix = tmp.newFolder("prefix")
        val request = request(prefix).apply {
            linkManifest = listOf("lib/escape\t../../outside")
        }

        val result = SealedInstaller.install(request)

        assertTrue(result.summary(), result.problems.any { it.startsWith("unsafe link line") })
        assertFalse(File(tmp.root, "outside").exists())
    }

    @Test
    fun installedPrefixIsPublishable() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")
        SealedInstaller.install(request(prefix))

        val report = ExecAudit.audit(prefix, nativeDir)

        assertTrue(report.toText(), report.publishable())
        assertEquals(1, report.shippedCount())
        assertEquals(0, report.outsideApk().size)
    }

    @Test
    fun anExecutableLeftInAppDataIsNotPublishable() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")
        SealedInstaller.install(request(prefix, execManifest = emptyList()))
        val stray = File(prefix, "bin/git")
        stray.parentFile?.mkdirs()
        stray.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        stray.setExecutable(true)

        val report = ExecAudit.audit(prefix, nativeDir)

        assertFalse(report.toText(), report.publishable())
        assertEquals(listOf("bin/git"), report.outsideApk().map { it.path })
    }

    @Test
    fun uninstall_reclaimsExactlyWhatWasInstalled() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")
        SealedInstaller.install(request(prefix))
        val execLines = listOf("bin/git\tlibgit.so")
        val manifest = manifestLines()

        val removed = SealedUninstaller.remove(prefix, execLines, manifest)

        assertTrue(removed.summary(), removed.ok())
        assertEquals(3, removed.files) // one executable link + two data files
        assertEquals(
            ("export PATH=/usr/bin\n".toByteArray().size + "welcome\n".toByteArray().size).toLong(),
            removed.bytes
        )
        assertFalse(File(prefix, "bin/git").exists())
        assertFalse(File(prefix, "etc/profile").exists())
        // The APK's own file is never touched: removing it would brick the app.
        assertTrue(File(nativeDir, "libgit.so").isFile)
    }

    @Test
    fun uninstall_leavesAFileTheUserChanged() {
        elf("libgit.so")
        val prefix = tmp.newFolder("prefix")
        SealedInstaller.install(request(prefix))
        File(prefix, "etc/profile").writeText("the user's own profile\n")

        val removed = SealedUninstaller.remove(prefix, listOf("bin/git\tlibgit.so"), manifestLines())

        assertEquals("the user's own profile\n", File(prefix, "etc/profile").readText())
        // Two, not one: the executable link, plus share/motd - which the payload
        // wrote and nobody touched, so it is still reclaimed. Only a file whose
        // size no longer matches its manifest record is treated as someone
        // else's.
        assertEquals(2, removed.files)
        assertFalse(File(prefix, "share/motd").exists())
    }

    @Test
    fun uninstall_onNothingIsNotAnError() {
        val removed = SealedUninstaller.remove(tmp.newFolder("empty"), null, null)
        assertTrue(removed.summary(), removed.ok())
        assertEquals(0, removed.files)
    }
}
