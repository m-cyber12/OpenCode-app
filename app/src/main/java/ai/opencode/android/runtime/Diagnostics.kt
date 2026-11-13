package ai.opencode.android.runtime

import android.content.Context
import ai.opencode.android.security.SecretStore
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
            sb.appendLine("collected_at=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())}")
            sb.appendLine()
            sb.appendLine("## Device")
            sb.appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
            sb.appendLine("android_release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            sb.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            sb.appendLine("primary_abi=${device?.primaryAbi}")
            sb.appendLine("app_package=${context.packageName}")
            sb.appendLine("app_version_code=${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(-1)}")
            sb.appendLine()
            // Same rule as RuntimeManager: V2 only when the APK carries both the Node 26
            // binary and the v2 bundle. The v1 constants below describe V1 only.
            val hasV2Asset = runCatching { context.assets.list("")?.contains(ServerEngine.V2_ASSET) == true }
                .getOrDefault(false)
            val engine = ServerEngine.select(hasNode26Binary = paths.node26Binary().isFile, hasV2Asset = hasV2Asset)
            val v2 = engine == ServerEngine.V2
            sb.appendLine("## Runtime versions")
            sb.appendLine("engine=$engine")
            sb.appendLine("payload_version=${RuntimeVersion.PAYLOAD_VERSION}")
            if (v2) {
                sb.appendLine("opencode=${ServerEngine.V2_VERSION} runtime=node26 (libnode26.so; v2 bundle)")
                sb.appendLine("git=${RuntimeVersion.GIT_VERSION} ripgrep=${RuntimeVersion.RIPGREP_VERSION}")
            } else {
                sb.appendLine("opencode=${RuntimeVersion.OPENCODE_VERSION} commit=${RuntimeVersion.OPENCODE_COMMIT}")
                sb.appendLine("bun=${RuntimeVersion.BUN_VERSION} git=${RuntimeVersion.GIT_VERSION} ripgrep=${RuntimeVersion.RIPGREP_VERSION}")
            }
            state?.manifest?.takeIf { !v2 }?.let {
                sb.appendLine("installed_manifest: opencode=${it.opencodeVersion} @ ${it.opencodeCommit.take(7)} bun=${it.bunVersion} git=${it.gitVersion} rg=${it.rgVersion} files=${it.entries.size}")
            }
            sb.appendLine()
            sb.appendLine("## State")
            sb.appendLine("status=${state?.status} detail=${state?.detail}")
            sb.appendLine("restart_count=${state?.restartCount}")
            sb.appendLine("provider_keys_in_keystore=${Secrets.storedProviderIds(context).joinToString(",") { it.ifEmpty { "?" } }}")
            sb.appendLine()
            sb.appendLine("## Phase 5 integration (loopback bind + credentials)")
            sb.appendLine("server_bind_policy=loopback-only allowed_hosts=127.0.0.1,localhost,::1")
            sb.appendLine("plaintext_password_file_present=${paths.serverPasswordFile.exists()}")
            sb.appendLine("secret_store_dir=${paths.secretsDir} entries_encrypted=${SecretStore.get(context).entries().size}")
            SecretStore.get(context).entries().forEach { e ->
                sb.appendLine("  secret ${e.name}: blob=${e.sizeBytes}B mtime=${e.modifiedMs} (ciphertext only)")
            }
            sb.appendLine("keystore_hardware_backed=${runCatching { SecretStore.get(context).isHardwareBacked() }.getOrDefault(false)}")
            sb.appendLine("provider_credentials=${Secrets.storedProviderIds(context).joinToString(",").ifEmpty { "(none configured)" }}")
            sb.appendLine("integration=${state?.integration?.ifEmpty { "(not run yet)" } ?: "(not run yet)"}")
            if (paths.loopbackAuditFile.isFile) {
                sb.appendLine("--- ${paths.loopbackAuditFile.name} ---")
                sb.appendLine(paths.loopbackAuditFile.readText().take(6000))
            }
            sb.appendLine()

            sb.appendLine("## Node 26 spike probe (test build only)")
            sb.appendLine(Node26Probe.report(paths.nativeLibraryDir, paths.filesDir))
            sb.appendLine()

            sb.appendLine("## OpenCode v2 on Node 26 probe (test build only)")
            sb.appendLine(Node26V2Probe.report(context, paths.nativeLibraryDir, paths.filesDir))
            sb.appendLine()

            sb.appendLine("## Layout")
            sb.appendLine("filesDir=${paths.filesDir}")
            sb.appendLine("nativeLibraryDir=${paths.nativeLibraryDir}")
            val layout = if (v2) {
                listOf(
                    "node26(libnode26.so)" to paths.node26Binary(),
                    "git(libgit.so)" to File(paths.nativeLibraryDir, "libgit.so"),
                    "git-remote-https(libgitremotehttp.so)" to File(paths.nativeLibraryDir, "libgitremotehttp.so"),
                    "rg(librg.so)" to File(paths.nativeLibraryDir, "librg.so"),
                    "child-shim" to paths.childShimBinary(),
                    "v2 server bundle" to paths.v2Entry, "v2 launcher" to paths.v2Launcher,
                    "bin/git" to paths.gitLink, "bin/rg" to paths.rgLink,
                    "v2 marker" to paths.v2Marker,
                )
            } else {
                listOf(
                    "bun" to paths.bunBinary(),
                    "git(libgit.so)" to File(paths.nativeLibraryDir, "libgit.so"),
                    "git-remote-https(libgitremotehttp.so)" to File(paths.nativeLibraryDir, "libgitremotehttp.so"),
                    "rg(librg.so)" to File(paths.nativeLibraryDir, "librg.so"),
                    "child-shim" to paths.childShimBinary(),
                    "server bundle" to paths.serverBundle, "launcher" to paths.launcher,
                    "bin/bun" to paths.bunLink, "bin/git" to paths.gitLink, "bin/rg" to paths.rgLink,
                    "marker" to paths.extractionMarker,
                )
            }
            layout.forEach { (name, f) ->
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
            if (v2) {
                // The v2 server's log location is not confirmed yet, so list what exists under the state folder.
                sb.appendLine("v2 state files under ${paths.xdgState} (name size):")
                paths.xdgState.walkTopDown().maxDepth(4).filter { it.isFile }.take(40).forEach {
                    sb.appendLine("  ${it.relativeTo(paths.xdgState).path} ${it.length()}")
                }
                sb.appendLine("phase2_invariants=NOT_PROBED (the config guard and the no-exec rewrite run only inside a model tool call; no model is reachable without a paid key)")
            }
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
