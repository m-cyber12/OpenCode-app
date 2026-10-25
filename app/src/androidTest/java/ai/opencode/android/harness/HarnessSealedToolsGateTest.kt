package ai.opencode.android.harness

import ai.opencode.android.harness.sealed.HarnessSeal
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimePaths
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

    /**
     * V3: the agent's environment - not ours - finds the shell.
     *
     * V1 and V2 build a PATH by hand to prove the sealed binaries run. The
     * agent does not get a hand-built PATH: it gets [RuntimeEnv.build], which
     * is also what the server process is launched with. If that block forgot
     * bin/, or pointed SHELL at /system/bin/sh, V1 and V2 would still pass and
     * the agent would still be running the device's shell. So run the same
     * pipeline through the real environment block.
     */
    @Test
    fun v3_theAgentsEnvironmentResolvesTheSealedShell() {
        // Wrapped: a throw here is a missing gate line, and a missing line is
        // the one failure this project cannot afford (a gate that cannot fail
        // the build). The exception goes into the detail instead.
        val measured = runCatching {
            HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
            val paths = RuntimePaths.get(context)
            val env = RuntimeEnv.build(paths, HarnessSeal.abi(), "gate")
            val bin = paths.binDir.absolutePath
            val firstOnPath = env["PATH"]?.split(File.pathSeparator)?.firstOrNull()
            val shellEnv = env["SHELL"]
            // Second line: where `tr` actually resolved. /system/bin has its
            // own toybox, so a PATH that merely "contains" bin/ would still
            // hand the agent the device's tools - this names the winner.
            val run = shellIn(env, "echo hello | tr a-z A-Z | grep -c HELLO; command -v tr")
            val lines = run.output.trim().split("\n").map { it.trim() }
            val resolvedTr = lines.getOrNull(1) ?: ""
            val ok = firstOnPath == bin && shellEnv == File(paths.binDir, "sh").absolutePath &&
                run.ran && run.exitCode == 0 && lines.firstOrNull() == "1" &&
                resolvedTr.startsWith(bin + File.separator)
            val detail = "agent env PATH[0]=$firstOnPath (want $bin) SHELL=$shellEnv " +
                "ran=${run.ran} exit=${run.exitCode} out='${lines.firstOrNull()}' tr=$resolvedTr"
            ok to detail
        }.getOrElse { t -> false to "threw ${t.javaClass.simpleName}: ${t.message}" }
        printGate("V3", measured.first, measured.second)
    }

    /**
     * V4: curl runs, and it can trust anything.
     *
     * The shipped curl is deliberately built WITHOUT a CA bundle, so trusting
     * the wrong CAs forever was traded for trusting the device's store at
     * runtime. That trade is invisible until it fails: without CURL_CA_PATH
     * every https request dies with a certificate error that reads like the
     * network being down. So the gate asserts the env var AND that it points
     * at a directory that exists, not merely that the binary starts.
     *
     * The pipeline (`curl --version | head -1`) is doing double duty: `head`
     * is one of the sealed coreutils, so this is also the pipeline path again.
     */
    @Test
    fun v4_curlRunsAndIsPointedAtTheDeviceCaStore() {
        val measured = runCatching {
            HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
            val paths = RuntimePaths.get(context)
            val env = RuntimeEnv.build(paths, HarnessSeal.abi(), "gate")
            val caPath = env["CURL_CA_PATH"]
            val run = shellIn(env, "curl --version | head -1")
            val caOk = !caPath.isNullOrBlank() && File(caPath).isDirectory
            val ok = run.ran && run.exitCode == 0 && run.output.contains("curl") && caOk
            val detail = "curl ran=${run.ran} exit=${run.exitCode} " +
                "out='${run.output.trim().take(60)}' CURL_CA_PATH=$caPath isDir=$caOk"
            ok to detail
        }.getOrElse { t -> false to "threw ${t.javaClass.simpleName}: ${t.message}" }
        printGate("V4", measured.first, measured.second)
    }

    /**
     * V5: jq runs, and its regex functions work.
     *
     * The second half is the point. jq can be built without oniguruma, and
     * such a jq still starts, still prints its version, and still answers
     * `.a` - it only fails when something calls `test`, `match` or `sub`, at
     * which point the agent gets "jq was built without oniguruma support"
     * mid-pipeline. So the gate calls one: if oniguruma was dropped, this
     * fails here rather than in the middle of somebody's JSON.
     */
    @Test
    fun v5_jqRunsWithRegexSupport() {
        val measured = runCatching {
            HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
            val paths = RuntimePaths.get(context)
            val env = RuntimeEnv.build(paths, HarnessSeal.abi(), "gate")
            val run = shellIn(env, "echo '{\"a\":1}' | jq -r .a; echo '\"abc\"' | jq -r 'test(\"b\")'")
            val lines = run.output.trim().split("\n").map { it.trim() }
            val ok = run.ran && run.exitCode == 0 &&
                lines.firstOrNull() == "1" && lines.getOrNull(1) == "true"
            val detail = "jq ran=${run.ran} exit=${run.exitCode} field='${lines.firstOrNull()}' " +
                "regex='${lines.getOrNull(1)}' (want 1 then true)"
            ok to detail
        }.getOrElse { t -> false to "threw ${t.javaClass.simpleName}: ${t.message}" }
        printGate("V5", measured.first, measured.second)
    }

    /**
     * V6: python runs, and its stdlib is the zip.
     *
     * The interpreter alone is not the claim. Two things make python work
     * here and both fail quietly: the extension modules (zlib, _socket,
     * _json...) are linked into the binary because Android cannot dlopen
     * from app data, and the stdlib is a single source zip found through an
     * explicit PYTHONHOME/PYTHONPATH rather than an installed prefix that does
     * not exist on a phone. So the gate imports three C modules and two
     * pure-Python packages and checks a hash - if either half is missing,
     * `import zlib` or `import json` fails.
     */
    @Test
    fun v6_pythonRunsFromTheSealedPrefix() {
        val measured = runCatching {
            HarnessSeal.apply(context, context.filesDir, nativeLibraryDir)
            val paths = RuntimePaths.get(context)
            val env = RuntimeEnv.build(paths, HarnessSeal.abi(), "gate")
            // The shell quotes must be OUTSIDE Python's single quotes. The
            // previous inverse (`-c '... {'ok': True} ...'`) was valid shell,
            // but adjacent quote segments stripped the inner quotes and Python
            // received `{ok: True}` / `babc` (board 40200765125: NameError).
            val script = "python3 -c \"import zlib, socket, json, pathlib, decimal; " +
                "print(json.dumps({'ok': True})); print(zlib.crc32(b'abc'))\""
            val run = shellIn(env, script)
            val lines = run.output.trim().split("\n").map { it.trim() }
            // crc32(b'abc') == 891568578, and the stdlib zip is what makes
            // json importable at all - no installed prefix exists on a phone.
            val ok = run.ran && run.exitCode == 0 &&
                lines.firstOrNull() == "{\"ok\": true}" && lines.getOrNull(1) == "891568578"
            val compact = run.output.trim().replace("\n", " <NL> ").take(800)
            val detail = "python ran=${run.ran} exit=${run.exitCode} json='${lines.firstOrNull()}' " +
                "zlib.crc32='${lines.getOrNull(1)}' (want 891568578) output='$compact'"
            ok to detail
        }.getOrElse { t -> false to "threw ${t.javaClass.simpleName}: ${t.message}" }
        printGate("V6", measured.first, measured.second)
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

    /** Runs through the SAME environment block the server is launched with. */
    private fun shellIn(env: Map<String, String>, script: String): Run =
        try {
            val process = ProcessBuilder(env["SHELL"], "-c", script)
                .directory(context.filesDir)
                .apply {
                    environment().clear()
                    environment().putAll(env)
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
