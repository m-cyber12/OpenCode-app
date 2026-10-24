package ai.opencode.android.harness

import ai.opencode.android.harness.sealed.HarnessSeal
import ai.opencode.android.ui.printGate
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1-V2: the userland core runs, from the prefix, on this device.
 *
 * X1-X3 measured the platform's rule, S1-S4 the installer, T1-T3 the runtime
 * this app already shipped. This is the first gate that runs a *userland*: a
 * real shell (`bin/sh` -> `libmksh.so`, the shell Android itself shipped for
 * years) and ~190 coreutils (one `libtoybox.so`, dispatching on argv[0], the
 * shape AOSP builds for bionic).
 *
 * Both are ordinary symlinks into the APK's native library directory, created
 * by the same sealed installer as everything else. Nothing here is extracted
 * or copied into app data, so nothing here needs the exec exemption Android 10
 * removed.
 *
 * V2 is the one that matters: a pipeline. `sh` forks, and each stage is found
 * by NAME on PATH and exec'd from a different prefix path - which is what
 * "the agent has a shell" has to mean, not just "a shell binary exists".
 */
@RunWith(AndroidJUnit4::class)
class HarnessSealedToolsGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val nativeLibraryDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val binDir: File get() = File(context.filesDir, "bin")

    @Test
    fun v1_theSealedShellRuns() {
        HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
        val run = shell("echo $SHELL_MARKER")
        val ok = run.ran && run.exitCode == 0 && run.output.contains(SHELL_MARKER)
        printGate(
            "V1",
            ok,
            "sealed shell ran=${run.ran} exit=${run.exitCode} api=${Build.VERSION.SDK_INT} " +
                ":: ${run.output.trim().take(80)}",
        )
    }

    @Test
    fun v2_aPipelineRunsThroughSealedTools() {
        HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
        // sh forks, then finds tr and grep by NAME on PATH: three different
        // prefix paths, three execs, all resolved into the APK's library dir.
        val run = shell("echo hello | tr a-z A-Z | grep -c HELLO")
        val ok = run.ran && run.exitCode == 0 && run.output.trim() == "1"
        printGate(
            "V2",
            ok,
            "pipeline ran=${run.ran} exit=${run.exitCode} output='${run.output.trim().take(40)}' " +
                "(expected '1') :: ${run.output.trim().take(80)}",
        )
    }

    private fun shell(script: String): Run =
        try {
            val process = ProcessBuilder(File(binDir, "sh").absolutePath, "-c", script)
                .directory(context.filesDir)
                .apply {
                    environment()["PATH"] = binDir.absolutePath + ":/system/bin:/system/xbin"
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = File(context.filesDir, "tmp").absolutePath
                }
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            Run(ran = true, exitCode = process.waitFor(), output = out)
        } catch (t: Throwable) {
            Run(ran = false, exitCode = -1, output = t.javaClass.simpleName + ": " + (t.message ?: ""))
        }

    private data class Run(val ran: Boolean, val exitCode: Int, val output: String)

    private companion object {
        const val SHELL_MARKER = "OC_SEALED_SH_OK"
    }
}
