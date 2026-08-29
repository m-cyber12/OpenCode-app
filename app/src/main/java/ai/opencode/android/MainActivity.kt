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
 * Phase 4 host UI: shows runtime status (the supervisor StateFlow) and
 * diagnostics, with start/stop controls. The chat/project UX is Phase 5 — this
 * screen exists so the app "owns" the runtime and troubleshooting is visible.
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    RuntimeScreen(
                        onStart = { RuntimeService.start(this) },
                        onStop = { RuntimeService.stop(this) },
                        onShare = { shareDiagnostics() },
                    )
                }
            }
        }
        // The runtime is part of opening the app: start on launch.
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

@Composable
private fun RuntimeScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { RuntimeManager.get(context) }
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var diagText by remember { mutableStateOf("(press Collect diagnostics)") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenCode runtime host", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status: ${state.status}", style = MaterialTheme.typography.titleMedium)
                Text(state.detail, style = MaterialTheme.typography.bodyMedium)
                Text("ABI: ${state.abi ?: "—"}   restarts: ${state.restartCount}")
                Text(
                    "OpenCode ${state.manifest?.opencodeVersion ?: "…"} @ ${
                        state.manifest?.opencodeCommit?.take(7) ?: "…"
                    }  •  bun ${state.manifest?.bunVersion ?: "…"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state.status != RuntimeStatus.HEALTHY
                && state.status != RuntimeStatus.STARTING && state.status != RuntimeStatus.EXTRACTING) {
                Text("Start")
            }
            Button(onClick = onStop, enabled = state.status == RuntimeStatus.HEALTHY
                || state.status == RuntimeStatus.STARTING || state.status == RuntimeStatus.CRASHED_RESTARTING) {
                Text("Stop")
            }
            Button(onClick = {
                scope.launch {
                    diagText = withContext(Dispatchers.IO) {
                        manager.diagnostics().text
                    }
                }
            }) { Text("Collect diagnostics") }
            Button(onClick = onShare) { Text("Share") }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(
                diagText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
