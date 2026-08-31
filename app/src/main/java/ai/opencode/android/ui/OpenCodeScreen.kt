package ai.opencode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.opencode.android.AppContainer
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimeService
import ai.opencode.android.runtime.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 5 client screen. Deliberately plain (UI polish is Phase 6): it renders
 * what the OpenCode server reports and sends user intent back through the
 * server's own endpoints — no local chat state machine, no local permission
 * decisions, no local MCP client.
 */
@Composable
fun OpenCodeRoot(onShare: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val manager = remember { RuntimeManager.get(context) }
    val runtime by manager.state.collectAsState()

    val workspacesRoot = remember { container.workspacesRoot().absolutePath }
    var workspace by remember { mutableStateOf(File(workspacesRoot, "mobile").absolutePath) }
    val repo = remember(workspace) { container.repositoryFor(workspace) }
    val state by repo.state.collectAsState()

    // The stream follows the server's lifecycle: subscribe while the supervisor
    // reports HEALTHY, drop the subscription when the runtime goes down.
    LaunchedEffect(runtime.status, workspace) {
        if (runtime.status == RuntimeStatus.HEALTHY) repo.startStream() else repo.stopStream()
    }
    DisposableEffect(repo) {
        onDispose { /* the repository is cached per workspace; the stream is
                        re-used across recompositions and stopped with the runtime */ }
    }

    var tab by remember { mutableStateOf(Tabs.CHAT) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusHeader(
                runtimeStatus = runtime.status.name,
                runtimeDetail = runtime.detail,
                integration = runtime.integration,
                serverVersion = state.serverVersion,
                streamStatus = state.streamStatus,
                workspace = workspace,
                onNewWorkspace = { name ->
                    val dir = File(workspacesRoot, name).apply { mkdirs() }
                    workspace = dir.absolutePath
                },
                onRestartRuntime = { RuntimeService.start(context) },
                onStopRuntime = { RuntimeService.stop(context) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tabs.ALL.forEach { t ->
                    if (t == tab) Button(onClick = { tab = t }) { Text(t.label) }
                    else OutlinedButton(onClick = { tab = t }) { Text(t.label) }
                }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    Tabs.CHAT -> Scrollable { ChatTab(repo, state) }
                    Tabs.PERMISSIONS -> Scrollable { PermissionsTab(repo, state) }
                    Tabs.MCP -> Scrollable { McpTab(repo, state) }
                    Tabs.CREDENTIALS -> Scrollable { CredentialsTab(container, repo, state) }
                    Tabs.RUNTIME -> Scrollable { RuntimeTab(manager, onShare) }
                }
            }
        }
    }
}

private enum class Tabs(val label: String) {
    CHAT("Chat"),
    PERMISSIONS("Approvals"),
    MCP("MCP"),
    CREDENTIALS("Credentials"),
    RUNTIME("Runtime"),
    ;

    companion object {
        val ALL = entries.toList()
    }
}

@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun StatusHeader(
    runtimeStatus: String,
    runtimeDetail: String,
    integration: String,
    serverVersion: String,
    streamStatus: String,
    workspace: String,
    onNewWorkspace: (String) -> Unit,
    onRestartRuntime: () -> Unit,
    onStopRuntime: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("runtime=$runtimeStatus  server=$serverVersion", style = MaterialTheme.typography.titleSmall)
            Text(runtimeDetail, style = MaterialTheme.typography.bodySmall)
            Text("events: $streamStatus", style = MaterialTheme.typography.bodySmall)
            Text("workspace=$workspace", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (integration.isNotEmpty()) {
                Text("loopback/credentials: $integration", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("new project name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(onClick = { if (name.isNotBlank()) { onNewWorkspace(name.trim()); name = "" } }) { Text("Use") }
                OutlinedButton(onClick = onRestartRuntime) { Text("Start runtime") }
                OutlinedButton(onClick = onStopRuntime) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun ChatTab(repo: OpenCodeRepository, state: OpenCodeRepository.UiState) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { repo.newSession(null) }) { Text("New session") }
            TextButton(onClick = { repo.refresh() }) { Text("Refresh") }
            Button(onClick = { repo.abort() }, enabled = state.busy) { Text("Interrupt") }
        }
        Text(
            "sessions: ${state.sessions.size}" +
                if (state.selectedSession.isEmpty()) " (none selected)" else "  active=${state.selectedSession.take(16)}",
            style = MaterialTheme.typography.bodySmall,
        )
        state.sessions.take(8).forEach { s ->
            val selected = s.id == state.selectedSession
            TextButton(onClick = { repo.selectSession(s.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (if (selected) "* " else "  ") + (s.title.ifBlank { s.id.take(12) }) + "  [" + s.id.take(12) + "]",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider()
        val view = state.transcript.sessions.firstOrNull { it.sessionID == state.selectedSession }
        val messages = view?.messages ?: emptyList()
        if (messages.isEmpty()) {
            Text(
                "No messages yet. What you type goes to POST /session/:id/prompt_async; the reply streams back " +
                    "over the server's own event stream.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            messages.forEach { MessageCard(it) }
        }
        HorizontalDivider()
        state.error.takeIf { it.isNotEmpty() }?.let {
            Text("error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.notice.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(if (state.busy) "a turn is running…" else "ask OpenCode, or a command with Shell") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        repo.sendPrompt(text)
                        input = ""
                    }
                },
            ) { Text("Send") }
            OutlinedButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        repo.runShell(text)
                        input = ""
                    }
                },
            ) { Text("Shell") }
        }
    }
}

@Composable
private fun MessageCard(m: Transcript.Message) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.role.uppercase(), style = MaterialTheme.typography.labelMedium)
            m.parts.forEach { p ->
                when (p.type) {
                    "text" -> if (p.text.isNotEmpty()) {
                        Text(p.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    "tool" -> Text(
                        "tool ${p.tool} [${p.status}] " + p.title.take(60) +
                            (if (p.input.isNotEmpty()) "\n  in:  ${p.input.take(220)}" else "") +
                            (if (p.output.isNotEmpty()) "\n  out: ${p.output.take(400)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    "reasoning" -> Text("(reasoning ${p.text.length} chars)", style = MaterialTheme.typography.bodySmall)
                    "patch" -> Text("patch (${p.text.length} chars)", style = MaterialTheme.typography.bodySmall)
                    "file" -> Text("file ${p.title.ifBlank { p.text.take(60) }}", style = MaterialTheme.typography.bodySmall)
                    else -> Text("· ${p.type}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PermissionsTab(repo: OpenCodeRepository, state: OpenCodeRepository.UiState) {
    val pending = state.transcript.sessions.flatMap { it.pending }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Pending requests come from GET /permission and are answered with POST /permission/:id/reply. The " +
                "server decides what each reply allows; the app keeps no policy of its own.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { repo.refresh() }) { Text("Refresh") }
        if (pending.isEmpty()) Text("No pending requests.", style = MaterialTheme.typography.bodyMedium)
        pending.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("allow ${p.permission}?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "session=${p.sessionID.take(16)} patterns=${p.patterns.joinToString(",").take(160)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (p.metadata.isNotEmpty()) {
                        Text(p.metadata.take(400), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { repo.replyPermission(p.id, "once") }) { Text("Once") }
                        Button(onClick = { repo.replyPermission(p.id, "always") }) { Text("Always") }
                        OutlinedButton(onClick = { repo.replyPermission(p.id, "reject") }) { Text("Reject") }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("bash policy (written to opencode.jsonc through PATCH /global/config)", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ask", "allow", "deny").forEach { v ->
                OutlinedButton(onClick = { repo.setBashPolicy(v) }) { Text("bash=$v") }
            }
        }
    }
}

@Composable
private fun McpTab(repo: OpenCodeRepository, state: OpenCodeRepository.UiState) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(false) }
    var persist by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Status is GET /mcp from the server. Adding one uses OpenCode's own POST /mcp (live instance) and " +
                "PATCH /global/config (durable). A local server must be an executable the runtime can actually " +
                "reach (its bundled bin/: bun, git, rg) — see the Phase 5 report for the per-transport limits.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.mcp.isEmpty()) Text("No MCP servers configured.", style = MaterialTheme.typography.bodyMedium)
        state.mcp.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$k: $v", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { repo.connectMcp(k) }) { Text("connect") }
                    TextButton(onClick = { repo.disconnectMcp(k) }) { Text("disable") }
                }
            }
        }
        HorizontalDivider()
        TextField(name, { name = it }, label = { Text("name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { remote = !remote }) { Text(if (remote) "remote (http/sse)" else "local (stdio)") }
            Button(onClick = { persist = !persist }) { Text(if (persist) "save to config" else "session only") }
        }
        if (remote) {
            TextField(
                url,
                { url = it },
                label = { Text("https://host:port/mcp") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            TextField(
                command,
                { command = it },
                label = { Text("command, e.g. /…/files/bin/bun /…/files/mcp/mcp-server.js") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Button(
            onClick = {
                val cfg = if (remote) {
                    JSONObject().put("type", "remote").put("url", url.trim())
                } else {
                    JSONObject()
                        .put("type", "local")
                        .put("command", JSONArray(command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }))
                }
                repo.addMcp(name.trim(), cfg, persist)
            },
            enabled = name.isNotBlank() && if (remote) url.isNotBlank() else command.isNotBlank(),
        ) { Text("Add MCP server") }
    }
}

@Composable
private fun CredentialsTab(container: AppContainer, repo: OpenCodeRepository, state: OpenCodeRepository.UiState) {
    var provider by remember { mutableStateOf("openrouter") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "A key entered here is encrypted under an Android Keystore key the app cannot export, and is handed to " +
                "OpenCode through its own auth endpoint (PUT /auth/:providerID). It is never bundled in the APK, " +
                "never written to a plaintext app file by the app, and never sent to anything except the provider " +
                "endpoint OpenCode itself picks.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Keystore-held: " + container.storedProviderIdsLabel() +
                " | hardware-backed keystore: " + container.hardwareBackedLabel(),
            style = MaterialTheme.typography.bodySmall,
        )
        state.providers?.let {
            Text("connected (per OpenCode): ${it.connected.joinToString(", ").ifEmpty { "(none)" }}",
                style = MaterialTheme.typography.bodySmall)
            Text("default model: ${it.defaultModel.entries.joinToString(", ").ifEmpty { "(none)" }}",
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        TextField(provider, { provider = it }, label = { Text("provider id") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextField(
            key,
            { key = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    repo.provisionProvider(provider.trim(), key.trim(), container.secrets)
                    key = ""
                },
                enabled = provider.isNotBlank() && key.isNotBlank(),
            ) { Text("Store + provision") }
            OutlinedButton(onClick = { repo.revokeProvider(provider.trim(), container.secrets) }) { Text("Revoke") }
        }
        HorizontalDivider()
        TextField(model, { model = it }, label = { Text("model override: providerID/modelID") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(
            onClick = {
                val parts = model.split("/", limit = 2)
                if (parts.size == 2) repo.setModel(parts[0], parts[1])
            },
            enabled = model.contains("/"),
        ) { Text("Use model") }
        state.model?.let { Text("sending model: ${it.providerID}/${it.modelID}", style = MaterialTheme.typography.bodySmall) }
        state.notice.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuntimeTab(manager: RuntimeManager, onShare: () -> Unit) {
    val state by manager.state.collectAsState()
    var diag by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                scope.launch { diag = withContext(Dispatchers.IO) { manager.diagnostics().text }.take(40_000) }
            }) { Text("Collect diagnostics") }
            OutlinedButton(onClick = onShare) { Text("Share bundle") }
        }
        Text("status=${state.status} restarts=${state.restartCount}", style = MaterialTheme.typography.bodySmall)
        state.manifest?.let {
            Text(
                "opencode ${it.opencodeVersion} @ ${it.opencodeCommit.take(7)}  bun ${it.bunVersion}  " +
                    "git ${it.gitVersion}  rg ${it.rgVersion}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "The runtime is supervised by a foreground service (long-running local compute). Everything on this " +
                "screen is the app acting as a client of the OpenCode server it owns.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (diag.isNotEmpty()) {
            Text(diag, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
