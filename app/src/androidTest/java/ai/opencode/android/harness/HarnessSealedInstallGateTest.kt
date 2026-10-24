package ai.opencode.android.harness

import ai.opencode.android.harness.sealed.ExecAudit
import ai.opencode.android.harness.sealed.RuntimeManifest
import ai.opencode.android.harness.sealed.SealedInstaller
import ai.opencode.android.harness.sealed.SealedUninstaller
import ai.opencode.android.ui.printGate
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1-S4: the sealed installer, on this device, with a payload this test builds.
 *
 * X1-X3 proved the platform's rule (the APK's native library directory
 * executes, app data does not, a prefix symlink runs). This proves the machinery
 * that will build a userland on that rule, rather than the rule itself: a
 * payload is installed into a prefix, every executable in it becomes a symlink
 * into the APK, and the result is then judged the way a real install will be -
 * by walking the prefix and asking whether anything left in app data would need
 * `execve()`.
 *
 * The executable used is `libocexecprobe.so`, the same PIE the exec-model gates
 * run, because this is about the packaging and not about the tool: if the sealed
 * shape did not execute, the probe's marker is what would be missing.
 *
 * Nothing here needs a server, a model, a network or a userland.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSealedInstallGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val nativeLibraryDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /** A prefix of our own, rebuilt from scratch on every run of the gate. */
    private fun freshPrefix(): File {
        val prefix = File(context.filesDir, "harness-seal-test")
        prefix.deleteRecursively()
        prefix.mkdirs()
        return prefix
    }

    /** The data half of a payload: one file, small enough to build in-process. */
    private fun payloadZip(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_PATH))
            zip.write(DATA_CONTENT.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun manifestLines(): List<String> {
        val size = DATA_CONTENT.toByteArray(StandardCharsets.UTF_8).size
        return listOf(RuntimeManifest.digest(DATA_CONTENT.toByteArray(StandardCharsets.UTF_8)) + "\t" + size + "\t" + DATA_PATH)
    }

    private fun install(prefix: File): SealedInstaller.Result =
        SealedInstaller.install(
            SealedInstaller.Request().apply {
                this.prefix = prefix
                this.nativeLibraryDirectory = nativeLibraryDir
                this.payload = ByteArrayInputStream(payloadZip())
                this.manifest = ByteArrayInputStream(manifestLines().joinToString("\n").toByteArray())
                this.execManifest = listOf("$LINK_PATH\t$PROBE_NAME")
            }
        )

    @Test
    fun s1_aPayloadInstallsIntoAPrefix() {
        val prefix = freshPrefix()
        val result = install(prefix)

        val ok = result.ok() && result.files == 1 && result.verified == 1 && result.executables == 1
        printGate(
            "S1",
            ok,
            "sealed install ok=${result.ok()} files=${result.files} verified=${result.verified} " +
                "executables=${result.executables} problems=${result.problems} :: ${result.summary()}",
        )
    }

    @Test
    fun s2_aSealedExecutableActuallyRunsFromItsPrefixPath() {
        val prefix = freshPrefix()
        install(prefix)
        val link = File(prefix, LINK_PATH)
        val isLink = runCatching { Files.isSymbolicLink(link.toPath()) }.getOrDefault(false)
        val ran = if (isLink) attempt(link) else Attempt(ran = false, exitCode = -1, output = "not a symlink")

        // The point of the whole exercise: a stable prefix path, whose inode
        // lives in the APK, executes on this device at this app's targetSdk.
        val ok = isLink && ran.ran && ran.exitCode == 0 && ran.output.contains(PROBE_MARKER)
        printGate(
            "S2",
            ok,
            "prefix exec symlink=$isLink ran=${ran.ran} exit=${ran.exitCode} " +
                "targetSdk=${context.applicationInfo.targetSdkVersion} api=${Build.VERSION.SDK_INT} :: " +
                ran.output.trim().take(120),
        )
    }

    @Test
    fun s3_theInstalledPrefixIsPublishable() {
        val prefix = freshPrefix()
        install(prefix)
        val report = ExecAudit.audit(prefix, nativeLibraryDir)

        // Re-derived from the filesystem, not from the installer's own counts:
        // nothing in the prefix needs the data-directory exec exemption.
        val ok = report.publishable() && report.shippedCount() == 1 && report.failure == null
        printGate(
            "S3",
            ok,
            "exec audit publishable=${report.publishable()} shipped=${report.shippedCount()} " +
                "outside=${report.outsideApk().map { it.path }} scanned=${report.scanned} " +
                "failure=${report.failure}",
        )
    }

    @Test
    fun s4_uninstallReclaimsExactlyWhatWasInstalled() {
        val prefix = freshPrefix()
        install(prefix)
        val probeInApk = File(nativeLibraryDir, PROBE_NAME)

        val removed = SealedUninstaller.remove(prefix, listOf("$LINK_PATH\t$PROBE_NAME"), manifestLines())

        // The executable link and the one data file; the APK's own copy of the
        // probe is not ours to delete, and removing it would brick the app.
        val ok = removed.ok() && removed.files == 2 && !File(prefix, LINK_PATH).exists() &&
            !File(prefix, DATA_PATH).exists() && probeInApk.isFile
        printGate(
            "S4",
            ok,
            "uninstall files=${removed.files} bytes=${removed.bytes} problems=${removed.problems} " +
                "probeStillInApk=${probeInApk.isFile} :: ${removed.summary()}",
        )
    }

    private fun attempt(file: File): Attempt =
        try {
            val process = ProcessBuilder(file.absolutePath)
                .directory(nativeLibraryDir)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            Attempt(ran = true, exitCode = process.waitFor(), output = out)
        } catch (t: Throwable) {
            Attempt(ran = false, exitCode = -1, output = t.javaClass.simpleName + ": " + (t.message ?: ""))
        }

    private data class Attempt(val ran: Boolean, val exitCode: Int, val output: String)

    private companion object {
        const val PROBE_NAME = "libocexecprobe.so"
        const val PROBE_MARKER = "OCEXECPROBE ok"
        const val LINK_PATH = "bin/ocprobe"
        const val DATA_PATH = "etc/motd"
        const val DATA_CONTENT = "sealed payload\n"
    }
}
