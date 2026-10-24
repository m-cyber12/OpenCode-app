package ai.opencode.android.harness

import ai.opencode.android.harness.sealed.ExecAudit
import ai.opencode.android.harness.sealed.HarnessSeal
import ai.opencode.android.harness.sealed.SealedLayout
import ai.opencode.android.harness.sealed.SealedLinks
import ai.opencode.android.ui.printGate
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T1-T3: the runtime this app already ships, sealed.
 *
 * S1-S4 proved the installer with a payload the test invented. This is the same
 * machinery pointed at the payload the build actually produces: the manifest
 * that ships inside the APK (`sealed-exec-<abi>.txt`) is applied by the app's
 * own [HarnessSeal], and the result is checked the way a real install has to be
 * — every tool resolves into the APK, two of them actually run from their
 * prefix paths, and an audit re-derives from the filesystem that nothing under
 * `filesDir/bin` needs the data-directory exec exemption.
 *
 * The links are the same five the app has always created (bun, git, rg and
 * git's two http helpers). What is new is that they now come from build output
 * through the kernel instead of from a hard-coded table, so a packaging mistake
 * shows up here rather than as a tool that is silently missing.
 *
 * No server, no model, no network. The server keeps running on Bun throughout.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSealedRuntimeGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val nativeLibraryDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val binDir: File get() = File(context.filesDir, "bin")

    /** Apply exactly what the app applies at startup; idempotent by design. */
    private fun seal(): Applied {
        val links = HarnessSeal.readManifest(context)
        return Applied(links, HarnessSeal.apply(context, context.filesDir, nativeLibraryDir))
    }

    @Test
    fun t1_theShippedManifestLinksEveryToolIntoTheApk() {
        var applied = seal()
        var unresolved = unresolvedLinks(applied.links)
        // The app re-links these at startup too, and a supervisor restart can
        // land in the middle of this gate. Applying again is the designed
        // behaviour (it is idempotent), so a second pass is a repair, not a
        // retry of a failure - and it is reported as one.
        var repaired = false
        if (unresolved.isNotEmpty()) {
            applied = seal()
            unresolved = unresolvedLinks(applied.links)
            repaired = true
        }
        val links = applied.links
        val named = links?.map { it.path }?.toSet() ?: emptySet()
        val missingTools = REQUIRED_TOOLS.filter { it !in named }
        val result = applied.result
        val ok = links != null && missingTools.isEmpty() && unresolved.isEmpty() &&
            result != null && result.ok() &&
            result.linked + result.alreadyCorrect == links.size
        printGate(
            "T1",
            ok,
            "sealed manifest links=${links?.size} missingTools=$missingTools " +
                "unresolved=$unresolved repaired=$repaired linked=${result?.linked} " +
                "alreadyCorrect=${result?.alreadyCorrect} errors=${result?.errors} " +
                "missingTargets=${result?.missingTargets}",
        )
    }

    /** Links that are not symlinks resolving into the APK's library directory. */
    private fun unresolvedLinks(links: List<SealedLayout.Link>?): List<String> =
        (links ?: emptyList()).filter { link ->
            val file = File(context.filesDir, link.path)
            if (!Files.isSymbolicLink(file.toPath())) return@filter true
            val target = SealedLinks.readLink(file)
            target == null || !File(target).absolutePath.startsWith(nativeLibraryDir.absolutePath)
        }.map { it.path }

    @Test
    fun t2_theRuntimeBinariesRunFromTheirPrefixPaths() {
        seal()
        val runs = TOOLS.map { (name, needle) -> name to attempt(File(binDir, name), needle) }
        val failed = runs.filter { !it.second.ok }.map { it.first }
        printGate(
            "T2",
            failed.isEmpty(),
            // '; ' and not ' | ': the gate runner truncates a detail at the
            // first ' | ', which hid git behind rg in the evidence summary.
            runs.joinToString("; ") { (name, run) ->
                "$name ran=${run.ran} exit=${run.exitCode} :: ${run.output.trim().take(40)}"
            } + " :: failed=$failed api=${Build.VERSION.SDK_INT} " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull()}",
        )
    }

    @Test
    fun t3_theRuntimePrefixIsPublishable() {
        seal()
        val report = ExecAudit.audit(binDir, nativeLibraryDir)
        val ok = report.publishable() && report.shippedCount() == REQUIRED_TOOLS.size &&
            report.failure == null
        printGate(
            "T3",
            ok,
            "exec audit publishable=${report.publishable()} shipped=${report.shippedCount()} " +
                "outside=${report.outsideApk().map { it.path }} scanned=${report.scanned} " +
                "failure=${report.failure}",
        )
    }

    private fun attempt(file: File, needle: String): Run =
        try {
            val process = ProcessBuilder(file.absolutePath, "--version")
                .directory(context.filesDir)
                .apply {
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = File(context.filesDir, "tmp").absolutePath
                    environment()["PATH"] = binDir.absolutePath + ":/system/bin:/system/xbin"
                }
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            Run(ran = true, exitCode = code, output = out, ok = code == 0 && out.contains(needle))
        } catch (t: Throwable) {
            Run(
                ran = false,
                exitCode = -1,
                output = t.javaClass.simpleName + ": " + (t.message ?: ""),
                ok = false,
            )
        }

    private data class Run(val ran: Boolean, val exitCode: Int, val output: String, val ok: Boolean)

    private data class Applied(
        val links: List<SealedLayout.Link>?,
        val result: SealedLayout.Result?,
    )

    private companion object {
        /** The tools the payload must seal, or the runtime is incomplete. */
        val REQUIRED_TOOLS = listOf(
            "bin/bun", "bin/git", "bin/rg", "bin/git-remote-http", "bin/git-remote-https",
        )

        /** Two of them are actually run: each must print its own name. */
        val TOOLS = listOf("rg" to "ripgrep", "git" to "git version")
    }
}
