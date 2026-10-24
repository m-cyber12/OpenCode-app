package ai.opencode.android.harness

import ai.opencode.android.ui.printGate
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.os.Build
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * X1-X3: the exec model the coding harness depends on, measured on this app,
 * on this device, at this app's targetSdk - not inherited from another
 * project's evidence.
 *
 * The rule (Android 10 / API 29 and later): an app may execute bytes that came
 * out of its APK - the platform extracts them into `nativeLibraryDir` - and may
 * NOT execute the same bytes once they live in its own data directory, because
 * writable-then-executable is a W^X violation. A real userland (bash, python,
 * node, git) is only possible on the legal side of that line, so before any of
 * it is built here the line is measured.
 *
 * The probe is `libocexecprobe.so`, a PIE executable packaged under a library
 * name so the platform extracts it (built by
 * `phase4/scripts/10-build-payload.sh` next to the runtime's own shims). It
 * writes one marker line and exits 0. There is no runtime, no server, no model
 * and no network in this class - by design, so it cannot flake.
 */
@RunWith(AndroidJUnit4::class)
class HarnessExecModelGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Where the platform puts the APK's native libraries - the executable path. */
    private val nativeLibraryDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val probe: File get() = File(nativeLibraryDir, PROBE_NAME)

    /**
     * Run [file] and return what came back: whether it ran at all, its exit
     * code, and whatever it printed. Both streams are drained before waiting,
     * so a chatty process cannot deadlock the gate.
     */
    private fun attempt(file: File): Result {
        return try {
            val process = ProcessBuilder(file.absolutePath)
                .directory(nativeLibraryDir)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            Result(ran = true, exitCode = code, output = out)
        } catch (t: Throwable) {
            // An exec that Android refuses surfaces right here, as an
            // IOException from the failed execve - that IS the measurement.
            Result(ran = false, exitCode = -1, output = t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    @Test
    fun x1_theApkNativeLibraryDirectoryIsExecutable() {
        if (!probe.isFile) {
            printGate("X1", false, "probe missing at ${probe.absolutePath} - the payload did not ship it")
            return
        }
        val r = attempt(probe)
        val marker = r.output.contains(PROBE_MARKER)
        val ok = r.ran && r.exitCode == 0 && marker
        printGate(
            "X1",
            ok,
            "nativeLibraryDir exec ran=${r.ran} exit=${r.exitCode} marker=$marker " +
                "targetSdk=${context.applicationInfo.targetSdkVersion} api=${Build.VERSION.SDK_INT} " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull()} :: ${r.output.trim().take(120)}",
        )
    }

    @Test
    fun x2_theSameBytesInAppDataAreRefused() {
        if (!probe.isFile) {
            printGate("X2", false, "probe missing at ${probe.absolutePath}")
            return
        }
        // The identical bytes, copied into the app's own data directory and
        // made executable by the app itself. If this runs, the W^X rule is not
        // being enforced here and the harness has no safe place to live.
        val copy = File(context.filesDir, "probe-exec")
        runCatching { copy.delete() }
        val copied = runCatching {
            probe.inputStream().use { it.copyTo(copy.outputStream()) }
            copy.setExecutable(true, true)
        }.isSuccess
        val r = if (copied) attempt(copy) else Result(false, -1, "copy failed")
        runCatching { copy.delete() }
        // Either the exec is refused, or it is refused *as* non-executable:
        // both are the platform holding the line. What must never happen is a
        // successful run that prints the marker.
        val refused = !r.ran || r.exitCode != 0 || !r.output.contains(PROBE_MARKER)
        printGate(
            "X2",
            refused && copied,
            "appData exec refused=$refused ran=${r.ran} exit=${r.exitCode} " +
                "api=${Build.VERSION.SDK_INT} targetSdk=${context.applicationInfo.targetSdkVersion} :: " +
                r.output.trim().take(120),
        )
    }

    @Test
    fun x3_aPrefixSymlinkIntoTheApkStillRuns() {
        if (!probe.isFile) {
            printGate("X3", false, "probe missing at ${probe.absolutePath}")
            return
        }
        // The shape the harness actually uses: a stable prefix path
        // (<files>/bin/probe) that is a symlink to the APK's copy. Scripts,
        // PATH entries and shebangs can then name the prefix forever while the
        // executable inode still comes from the APK.
        val bin = File(context.filesDir, "bin").apply { mkdirs() }
        val link = File(bin, "probe")
        runCatching { link.delete() }
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), probe.toPath())
        }.isSuccess
        val r = if (linked) attempt(link) else Result(false, -1, "symlink failed")
        runCatching { link.delete() }
        val ok = linked && r.ran && r.exitCode == 0 && r.output.contains(PROBE_MARKER)
        printGate(
            "X3",
            ok,
            "prefix symlink exec linked=$linked ran=${r.ran} exit=${r.exitCode} :: " +
                r.output.trim().take(120),
        )
    }

    private data class Result(val ran: Boolean, val exitCode: Int, val output: String)

    private companion object {
        const val PROBE_NAME = "libocexecprobe.so"
        const val PROBE_MARKER = "OCEXECPROBE ok"
    }
}
