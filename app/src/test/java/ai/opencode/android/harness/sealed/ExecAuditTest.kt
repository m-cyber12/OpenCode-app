package ai.opencode.android.harness.sealed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The audit that decides whether a prefix is publishable.
 *
 * It re-derives the answer from the filesystem instead of trusting the
 * installer's bookkeeping, which is what lets it catch drift: a symlink that now
 * points at a copy in app data, a package that replaced a link with a real file,
 * an upgrade that shipped a library under a new name.
 */
class ExecAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nativeDir: File by lazy { tmp.newFolder("native") }

    private fun prefix(): File = tmp.newFolder("prefix")

    private fun elf(dir: File, name: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02))
        file.setExecutable(true)
        return file
    }

    private fun link(from: File, to: File) {
        from.parentFile?.mkdirs()
        SealedLinks.create(from, to)
    }

    @Test
    fun aLinkIntoTheApkIsPublishable() {
        val prefix = prefix()
        link(File(prefix, "bin/git"), elf(nativeDir, "libgit.so"))

        val report = ExecAudit.audit(prefix, nativeDir)

        assertTrue(report.toText(), report.publishable())
        assertEquals(1, report.shippedCount())
    }

    @Test
    fun aBinaryInAppDataIsNotPublishable() {
        val prefix = prefix()
        elf(prefix, "bin/git")

        val report = ExecAudit.audit(prefix, nativeDir)

        assertFalse(report.toText(), report.publishable())
        assertEquals(1, report.outsideApk().size)
        assertTrue(report.toText().contains("targetSdk <= 28 only"))
    }

    @Test
    fun aScriptIsCountedButDoesNotBlockPublishing() {
        val prefix = prefix()
        val script = File(prefix, "bin/deploy")
        script.parentFile?.mkdirs()
        script.writeText("#!/usr/bin/env bash\necho hi\n")
        script.setExecutable(true)

        val report = ExecAudit.audit(prefix, nativeDir)

        assertTrue(report.toText(), report.publishable())
        assertEquals(1, report.outsideScripts().size)
        assertEquals(0, report.outsideApk().size)
    }

    @Test
    fun aDanglingLinkCannotBeExecutedSoItIsIgnored() {
        val prefix = prefix()
        link(File(prefix, "bin/gone"), File(nativeDir, "libgone.so"))

        val report = ExecAudit.audit(prefix, nativeDir)

        assertTrue(report.toText(), report.publishable())
        assertEquals(0, report.examined())
    }

    @Test
    fun packageDataIsSkipped() {
        val prefix = prefix()
        elf(prefix, "share/doc/example/page")
        elf(prefix, "node_modules/example/bin/tool")

        val report = ExecAudit.audit(prefix, nativeDir)

        assertEquals(0, report.examined())
    }

    @Test
    fun versionedLibraryLinksAreNotExecutables() {
        val prefix = prefix()
        val real = File(prefix, "lib/libssl.so.3")
        real.parentFile?.mkdirs()
        real.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        link(File(prefix, "lib/libssl.so"), real)

        val report = ExecAudit.audit(prefix, nativeDir)

        // dlopen()'d, never execve()'d, so it does not constrain packaging.
        assertTrue(report.toText(), report.publishable())
    }

    @Test
    fun anAbsentPrefixIsEmptyRatherThanFatal() {
        val report = ExecAudit.audit(File(tmp.root, "nope"), nativeDir)

        assertEquals(0, report.examined())
        assertFalse(report.truncated)
    }

    @Test
    fun aSymlinkLoopCannotHangTheAudit() {
        val prefix = prefix()
        File(prefix, "bin").mkdirs()
        SealedLinks.create(File(prefix, "bin/loop-a"), File(prefix, "bin/loop-b"))
        SealedLinks.create(File(prefix, "bin/loop-b"), File(prefix, "bin/loop-a"))

        val report = ExecAudit.audit(prefix, nativeDir)

        // Resolution is depth-bounded, and a loop resolves to nothing
        // executable, so a hostile or damaged prefix costs bounded time.
        assertNull(report.failure)
        assertTrue(report.toText(), report.publishable())
        assertEquals(0, report.examined())
    }
}
