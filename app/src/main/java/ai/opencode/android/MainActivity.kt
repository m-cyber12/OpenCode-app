package ai.opencode.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ai.opencode.android.runtime.RuntimeService
import ai.opencode.android.runtime.RuntimeStatus
import ai.opencode.android.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's screen. Phase 4 added the runtime host (status + diagnostics);
 * Phase 5 makes the app an actual OpenCode client: sessions, streaming,
 * tool parts, permission approvals, MCP and Keystore-backed credentials, all
 * driven through the upstream HTTP API and event stream (see ui/OpenCodeScreen
 * and client/). Polishing this UI is Phase 6.
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
            MaterialTheme {
                ai.opencode.android.ui.OpenCodeRoot(onShare = { shareDiagnostics() })
            }
        }
        // The runtime is part of opening the app: start on launch.
        RuntimeService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // Re-assert the runtime whenever the activity comes to the foreground.
        // onCreate only fires on a cold start; after a debug STOP tore the FGS
        // down (or the system dropped a backgrounded service), re-launching the
        // activity can arrive as onNewIntent/onStart without onCreate, so the
        // supervisor/FGS must be (re)started here too. start() is idempotent.
        RuntimeService.start(this)
    }

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
