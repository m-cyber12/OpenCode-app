package ai.opencode.android.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects everything needed to troubleshoot the embedded runtime into a
 * single shareable text bundle: host lifecycle log, OpenCode server log tail,
 * crash reports, runtime/package versions, device ABI and Android API level.
 * Credentials are never included (only whether a model key is present).
 */
data class Diagnostics(
    val text: String,
    val device: AbiGate.DeviceInfo?,
    val state: RuntimeState?,
) {
    companion object {
        fun collect(context: Context, paths: RuntimePaths, logger: RuntimeLogger, state: RuntimeState?): Diagnostics {
            val gate = AbiGate.evaluate()
            val device = (gate as? AbiGate.Result.Ok)?.device ?: (gate as? AbiGate.Result.Unsupported)?.device

            val sb = StringBuilder()
            sb.appendLine("# OpenCode Android — runtime diagnostics")
            sb.appendLine("collected_at=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}")
            sb.appendLine()
            sb.appendLine("## Device")
            sb.appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
            sb.appendLine("android_release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            sb.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            sb.appendLine("primary_abi=${device?.primaryAbi}")
            sb.appendLine("app_package=${context.packageName}")
            sb.appendLine("app_version_code=${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(-1)}")
            sb.appendLine()
            sb.appendLine("## Runtime versions")
            sb.appendLine("payload_version=${RuntimeVersion.PAYLOAD_VERSION}")
            sb.appendLine("opencode=${RuntimeVersion.OPENCODE_VERSION} commit=${RuntimeVersion.OPENCODE_COMMIT}")
            sb.appendLine("bun=${RuntimeVersion.BUN_VERSION} git=${RuntimeVersion.GIT_VERSION} ripgrep=${RuntimeVersion.RIPGREP_VERSION}")
            state?.manifest?.let {
                sb.appendLine("installed_manifest: opencode=${it.opencodeVersion} @ ${it.opencodeCommit.take(7)} bun=${it.bunVersion} git=${it.gitVersion} rg=${it.rgVersion} files=${it.entries.size}")
            }
            sb.appendLine()
            sb.appendLine("## State")
            sb.appendLine("status=${state?.status} detail=${state?.detail}")
            sb.appendLine("restart_count=${state?.restartCount}")
            sb.appendLine("model_key_present=${!Secrets.readApiKey(paths).isNullOrBlank()}")
            sb.appendLine()

            sb.appendLine("## Layout")
            sb.appendLine("filesDir=${paths.filesDir}")
            sb.appendLine("nativeLibraryDir=${paths.nativeLibraryDir}")
            listOf(
                "bun" to paths.bunBinary(),
                "git(libgit.so)" to File(paths.nativeLibraryDir, "libgit.so"),
                "rg(librg.so)" to File(paths.nativeLibraryDir, "librg.so"),
                "child-shim" to paths.childShimBinary(),
                "server bundle" to paths.serverBundle, "launcher" to paths.launcher,
                "bin/bun" to paths.bunLink, "bin/git" to paths.gitLink, "bin/rg" to paths.rgLink,
                "marker" to paths.extractionMarker,
            ).forEach { (name, f) ->
                sb.appendLine("  $name: exists=${f.exists()} exec=${runCatching { f.canExecute() }.getOrDefault(false)} size=${runCatching { f.length() }.getOrDefault(-1)} path=$f")
            }
            sb.appendLine()

            sb.appendLine("## Crashes")
            val crashes = paths.crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(5) ?: emptyList()
            if (crashes.isEmpty()) sb.appendLine("(none)")
            for (c in crashes) {
                sb.appendLine("--- ${c.name} ---")
                sb.appendLine(c.readText().take(4000))
            }
            sb.appendLine()

            sb.appendLine("## Host runtime log (tail 16 KiB)")
            sb.appendLine(logger.tail(16 * 1024))
            sb.appendLine()
            sb.appendLine("## OpenCode server log (XDG_STATE_HOME/opencode/log, tail 16 KiB)")
            val serverLogDir = File(paths.xdgState, "opencode/log")
            val serverLog = serverLogDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            sb.appendLine("server_log_file=${serverLog?.absolutePath ?: "(none found under $serverLogDir)"}")
            if (serverLog != null) {
                val len = serverLog.length()
                serverLog.inputStream().use { input ->
                    input.skip((len - 16 * 1024).coerceAtLeast(0))
                    sb.appendLine(input.bufferedReader().readText().take(16 * 1024))
                }
            }
            return Diagnostics(sb.toString(), device, state)
        }

        fun writeToFile(paths: RuntimePaths, text: String): File {
            paths.diagnosticsDir.mkdirs()
            val name = "diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val f = File(paths.diagnosticsDir, name)
            f.writeText(text)
            return f
        }
    }
}
