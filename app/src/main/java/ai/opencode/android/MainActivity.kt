package ai.opencode.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppRoot(onShareDiagnostics = { shareDiagnostics() })
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
}
