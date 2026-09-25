package ai.opencode.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimeService
import ai.opencode.android.ui.AppRoot

/**
 * The single activity: it owns the runtime lifecycle and nothing else.
 *
 * Phase 4 added the runtime host, Phase 5 made the app a real OpenCode client, and
 * Phase 6 replaced the developer surface with a product UI (`ui/AppRoot.kt`): the
 * activity starts the supervisor service, asks for the optional notification
 * permission, and hands the composition to [AppRoot], which is the only place in
 * the UI layer allowed to touch process singletons.
 *
 * The runtime is started on launch AND on every return to the foreground:
 * `RuntimeService.start` is idempotent, and after a debug STOP tore the foreground
 * service down (or the system dropped it in the background) a re-launch can arrive
 * as onNewIntent/onStart without onCreate.
 */
class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional; the FGS runs regardless on API < 33 or if denied */ }

    // `testTagsAsResourceId` is still experimental in the pinned Compose version.
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v8 beauty pass (M3-expressive brief): edge-to-edge. The canvas paints
        // under the system bars and AppRoot pads its content by the safe-drawing
        // insets, so the status bar sits on the app's own dark gold surface
        // instead of a grey system strip.
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            // PHASE 10 CONTINUATION. Expose the Compose test tags as resource ids in
            // the accessibility tree.
            //
            // Why this is a product decision, not a test hack: the signed release
            // build is not debuggable, so `run-as` does not exist there and the only
            // way to drive the shipped app from a desktop harness (or an
            // accessibility service, or the owner verifying a build by script) is
            // uiautomator's view of the real window. Without this, that view offers
            // only the rendered copy - and a script that taps "the button that says
            // Continue" breaks the day a string changes, while the app itself looks
            // ambiguous to anything else driving it. The tags are stable identifiers
            // (`welcome_continue`, `files_publish`, ...), they carry no user data,
            // and they are never spoken: resource ids do not affect what TalkBack
            // reads, which the U7 semantics audit still checks property by property.
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                AppRoot(
                    onShareDiagnostics = { shareDiagnostics() },
                    onOpenUrl = { url -> openUrl(url) },
                )
            }
        }
        RuntimeService.start(this)
    }

    override fun onStart() {
        super.onStart()
        RuntimeService.start(this)
    }

    /**
     * Share the diagnostics bundle. Collected on a worker thread, written into the
     * app's own files dir and handed over through the FileProvider - the same bundle
     * the Settings screen shows, and it never contains a credential.
     */
    private fun shareDiagnostics() {
        val mgr = RuntimeManager.get(this)
        Thread {
            val diag = mgr.diagnostics()
            val file = ai.opencode.android.runtime.Diagnostics.writeToFile(
                ai.opencode.android.runtime.RuntimePaths.get(this), diag.text,
            )
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OpenCode Android diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share OpenCode diagnostics"))
        }.start()
    }

    /**
     * Open one of the app's own informational links (upstream project, licence
     * texts, privacy policy, notices) in whatever browser the user has.
     *
     * Phase 10. The URLs live in strings.xml, not here: the UI layer must not
     * compile a URL literal (Phase 5's P5-G19 asserts the built app talks to
     * nothing but its own loopback server; phase6/scripts/check-ui-strings.py
     * enforces the same rule on the source). A missing browser is not a crash -
     * the failure is swallowed exactly like a missing share target.
     */
    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
