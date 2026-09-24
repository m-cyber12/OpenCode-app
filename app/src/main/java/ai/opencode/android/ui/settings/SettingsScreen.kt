package ai.opencode.android.ui.settings

import ai.opencode.android.BuildConfig
import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.ProviderSetup
import ai.opencode.android.memory.MemoryState
import ai.opencode.android.ui.common.DetailDisclosure
import ai.opencode.android.security.ProviderKeyring
import ai.opencode.android.ui.common.KeyValueRow
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.SectionCard
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.common.ThemeChoice
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.availabilityHeadline
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import ai.opencode.android.client.ModelRefCodec
import ai.opencode.android.client.ProviderCatalog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Runtime status, diagnostics, model, keys, MCP servers, permission policy and
 * appearance - the technical half of the app, one tap away from the conversation
 * and never in the way of it.
 *
 * Everything on this screen is a value the runtime or the server already reports:
 * [RuntimeSummary] (supervisor state, versions, device, loopback/credential audit),
 * [OpenCodeRepository.UiState] (server version, providers, model, MCP statuses with
 * upstream's own error text, stream status) and the diagnostics bundle collected by
 * the runtime layer and handed in as plain lines. The screen computes nothing about
 * the agent and caches nothing: it is a pure function of its parameters, which is
 * what lets the Phase 6 gates render it in any state.
 *
 * Two honesty rules it has to keep:
 *   * upstream status text is shown verbatim. An MCP server that reports
 *     `failed` with `Failed to get tools` says exactly that, next to the one line
 *     of explanation the Phase 5 report authorises (remote HTTP/SSE MCP servers are
 *     an upstream restriction on this runtime; local-process servers work). No
 *     retry loop, no softening, no client-side proxy.
 *   * `connected` for a provider is not a claim that a turn will run. The label says
 *     "key stored", because that is all the server is reporting.
 */
@Composable
fun SettingsScreen(
    runtime: RuntimeSummary,
    state: OpenCodeRepository.UiState,
    availability: AgentAvailability,
    diagnosticsLines: List<String>,
    diagnosticsLoading: Boolean,
    storedProviderIds: String,
    hardwareBacked: String,
    appVersion: String,
    theme: ThemeChoice,
    dynamicColor: Boolean,
    onThemeChange: (ThemeChoice) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSetModel: (String, String) -> Unit,
    onClearModel: () -> Unit,
    onSaveKey: (String, String) -> Unit,
    onRevokeKey: (String) -> Unit,
    // ---- Phase 10 continuation v4 -------------------------------------------------
    // Every addition is defaulted: the Phase 6 UI gates call this screen directly
    // with the parameters they care about, and a new control must not break them.
    /** v4 item 3: star/unstar one model for the chat header's quick switch. */
    onToggleStar: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** v4 item 2: one-step activation of a catalog provider (id, API key). */
    onConnectProvider: (String, String) -> Unit = { _, _ -> },
    // ---- v6.1: the per-provider keyring (several saved keys, one active) -----------
    /** The saved keys for a provider id (labels/last-4 only, never values). */
    providerKeys: (String) -> List<ProviderKeyring.Entry> = { emptyList() },
    /** Make a saved key (provider id, slot) the active one. */
    onUseKey: (String, String) -> Unit = { _, _ -> },
    /** Delete one saved key (provider id, slot). */
    onDeleteKey: (String, String) -> Unit = { _, _ -> },
    /** v4 item 2, manual path: (id, display name, base URL, model ids, key). */
    onAddCustomProvider: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    /** v4 items 1 and 4: the workspace folder, and the one action that changes it. */
    workspacePath: String = "",
    workspaceVisibleToFileManagers: Boolean = true,
    workspaceCanGrantAllFilesAccess: Boolean = false,
    workspacePendingMove: Int = 0,
    onPickWorkspace: () -> Unit = {},
    onGrantAllFilesAccess: () -> Unit = {},
    onMoveWorkspaceProjects: () -> Unit = {},
    onAddMcp: (String, String, Boolean) -> Unit,
    onConnectMcp: (String) -> Unit,
    onDisconnectMcp: (String) -> Unit,
    onRefreshMcp: () -> Unit,
    onBashPolicy: (String) -> Unit,
    providerSetup: ProviderSetup = ProviderSetup.UNKNOWN,
    permissionPolicy: Map<String, String> = emptyMap(),
    onPermissionPolicy: (String, String) -> Unit = { _, _ -> },
    memory: MemoryState = MemoryState(),
    onSaveMemory: (String, String) -> Unit = { _, _ -> },
    onRemoveMemory: (String) -> Unit = {},
    onShareDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    onRefreshDiagnostics: () -> Unit,
    onRestartRuntime: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A fixed, bounded list of sections rendered by a lazy list: the diagnostics
    // and model sections can be long, and their CONTENT scrolls inside a bounded
    // box rather than the screen nesting a lazy list inside a scrolling column.
    val sections = remember(
        runtime, state, availability, diagnosticsLines, diagnosticsLoading, storedProviderIds,
        hardwareBacked, appVersion, theme, dynamicColor, providerSetup, permissionPolicy, memory,
        workspacePath, workspaceVisibleToFileManagers, workspaceCanGrantAllFilesAccess, workspacePendingMove,
    ) {
        listOf(
            "runtime",
            "provider",
            "workspace",
            "diagnostics",
            "model",
            "keys",
            "mcp",
            "permissions",
            "memory",
            "appearance",
            "about",
            "opensource",
        )
    }

    Column(modifier = modifier.fillMaxSize().semantics { testTag = "settings_screen" }) {
        AppTopBar(
            title = stringResource(R.string.settings_title),
            subtitle = availabilityHeadline(availability),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { testTag = "settings_list" },
            // Phase 10 polish: same one-step-of-air treatment as the transcript.
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items = sections, key = { it }) { section ->
                when (section) {
                    "runtime" -> RuntimeSection(
                        runtime = runtime,
                        state = state,
                        availability = availability,
                        onRestart = onRestartRuntime,
                    )
                    "provider" -> ProviderSection(
                        providerSetup = providerSetup,
                        connectedCount = state.providers?.connected?.size ?: 0,
                    )
                    "workspace" -> WorkspaceSection(
                        path = workspacePath,
                        visibleToFileManagers = workspaceVisibleToFileManagers,
                        canGrantAllFilesAccess = workspaceCanGrantAllFilesAccess,
                        pendingMove = workspacePendingMove,
                        onPick = onPickWorkspace,
                        onGrant = onGrantAllFilesAccess,
                        onMove = onMoveWorkspaceProjects,
                    )
                    "diagnostics" -> DiagnosticsSection(
                        lines = diagnosticsLines,
                        loading = diagnosticsLoading,
                        onShare = onShareDiagnostics,
                        onCopy = onCopyDiagnostics,
                        onRefresh = onRefreshDiagnostics,
                    )
                    "model" -> ModelSection(
                        providers = state.providers,
                        model = state.model,
                        starred = state.starredModels,
                        storedProviderIds = storedProviderIds,
                        hardwareBacked = hardwareBacked,
                        onSetModel = onSetModel,
                        onClearModel = onClearModel,
                        onToggleStar = onToggleStar,
                        onConnectProvider = onConnectProvider,
                        onRevokeKey = onRevokeKey,
                        providerKeys = providerKeys,
                        onUseKey = onUseKey,
                        onDeleteKey = onDeleteKey,
                    )
                    "keys" -> KeysSection(
                        onAddCustomProvider = onAddCustomProvider,
                    )
                    "mcp" -> McpSection(
                        entries = state.mcp,
                        onAdd = onAddMcp,
                        onConnect = onConnectMcp,
                        onDisconnect = onDisconnectMcp,
                        onRefresh = onRefreshMcp,
                    )
                    "permissions" -> PermissionsSection(
                        policy = permissionPolicy,
                        onPolicy = onPermissionPolicy,
                        onBashPolicy = onBashPolicy,
                    )
                    "memory" -> MemorySection(
                        memory = memory,
                        onSave = onSaveMemory,
                        onRemove = onRemoveMemory,
                    )
                    "appearance" -> AppearanceSection(
                        theme = theme,
                        dynamicColor = dynamicColor,
                        onThemeChange = onThemeChange,
                        onDynamicColorChange = onDynamicColorChange,
                    )
                    "about" -> AboutSection(
                        appVersion = appVersion,
                        serverVersion = state.serverVersion,
                        onOpenUrl = onOpenUrl,
                    )
                    else -> OpenSourceSection(onOpenUrl = onOpenUrl)
                }
            }
        }
    }
}

// ---- runtime ---------------------------------------------------------------

@Composable
private fun RuntimeSection(
    runtime: RuntimeSummary,
    state: OpenCodeRepository.UiState,
    availability: AgentAvailability,
    onRestart: () -> Unit,
) {
    val chat = ChatTheme.chat
    SectionCard(title = stringResource(R.string.settings_section_runtime)) {
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_status),
            value = availabilityHeadline(runtime.availability),
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_status_raw),
            value = runtime.status.ifEmpty { "-" },
            mono = true,
        )
        if (runtime.detail.isNotBlank()) {
            KeyValueRow(
                label = stringResource(R.string.settings_runtime_detail),
                value = runtime.detail,
                mono = true,
            )
        }
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_restarts),
            value = runtime.restartCount.toString(),
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_version),
            value = runtime.opencodeVersion.ifEmpty { "-" },
            mono = true,
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_payload),
            value = runtime.payloadVersion.toString(),
            mono = true,
        )
        KeyValueRow(label = stringResource(R.string.settings_runtime_abi), value = runtime.abi.ifEmpty { "-" }, mono = true)
        KeyValueRow(label = stringResource(R.string.settings_runtime_device), value = runtime.device.ifEmpty { "-" })
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_android),
            value = runtime.androidVersion.ifEmpty { "-" },
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_server),
            value = stringResource(
                if (state.serverReachable) R.string.settings_runtime_server_reachable
                else R.string.settings_runtime_server_unreachable,
            ),
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_stream),
            value = state.streamStatus.ifEmpty { "-" },
            mono = true,
        )
        if (runtime.audit.isNotBlank()) {
            KeyValueRow(label = stringResource(R.string.settings_runtime_audit), value = runtime.audit, mono = true)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier.height(46.dp).semantics { testTag = "runtime_restart" },
        ) {
            Text(stringResource(R.string.settings_runtime_restart))
        }
    }
}

// ---- diagnostics -----------------------------------------------------------

@Composable
private fun DiagnosticsSection(
    lines: List<String>,
    loading: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit,
) {
    val chat = ChatTheme.chat
    SectionCard(
        title = stringResource(R.string.settings_section_diagnostics),
        body = stringResource(R.string.settings_diagnostics_body),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.height(46.dp).semantics { testTag = "diagnostics_refresh" },
            ) {
                Text(stringResource(R.string.action_retry))
            }
            TextButton(
                onClick = onCopy,
                modifier = Modifier.height(46.dp).semantics { testTag = "diagnostics_copy" },
            ) {
                Text(stringResource(R.string.settings_diagnostics_copy))
            }
            TextButton(
                onClick = onShare,
                modifier = Modifier.height(46.dp).semantics { testTag = "diagnostics_share" },
            ) {
                Text(stringResource(R.string.settings_diagnostics_share))
            }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = chat.muted)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_diagnostics_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.settings_diagnostics_lines, lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
            )
            DiagnosticsBody(lines.joinToString("\n"))
        }
    }
}

/**
 * The bundle in a bounded, scrollable box.
 *
 * Separate function on purpose: a screen that puts a scrolling block inside a lazy
 * list has to bound its height, and mixing the two in one function is how the
 * "infinity maximum height constraints" crash happens.
 */
@Composable
private fun DiagnosticsBody(text: String) {
    if (text.isBlank()) return
    Surface(
        color = ChatTheme.code.blockBackground,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { testTag = "diagnostics_body" },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(text = text, style = MonoSmall, color = ChatTheme.chat.muted)
        }
    }
}

// ---- model -----------------------------------------------------------------

@Composable
private fun ModelSection(
    providers: OpenCodeApi.ProviderSnapshot?,
    model: OpenCodeApi.ModelRef?,
    starred: List<OpenCodeApi.ModelRef>,
    storedProviderIds: String,
    hardwareBacked: String,
    onSetModel: (String, String) -> Unit,
    onClearModel: () -> Unit,
    onToggleStar: (String, String, Boolean) -> Unit,
    onConnectProvider: (String, String) -> Unit,
    onRevokeKey: (String) -> Unit,
    providerKeys: (String) -> List<ProviderKeyring.Entry> = { emptyList() },
    onUseKey: (String, String) -> Unit = { _, _ -> },
    onDeleteKey: (String, String) -> Unit = { _, _ -> },
) {
    val chat = ChatTheme.chat
    // v4 item 2: the catalog is hundreds of entries, so it is searched, not scrolled.
    var query by rememberSaveable { mutableStateOf("") }
    // v4 item 2: tapping a listed provider asks for the key and nothing else.
    var connectTarget by remember { mutableStateOf<OpenCodeApi.ProviderEntry?>(null) }

    SectionCard(title = stringResource(R.string.settings_section_model)) {
        val current = if (model == null) "" else ModelRefCodec.encode(model)
        KeyValueRow(
            label = stringResource(R.string.settings_model_current),
            value = current.ifEmpty { stringResource(R.string.settings_model_none) },
            mono = current.isNotEmpty(),
        )
        // The old keys card showed this and the catalog did not; with key management
        // on the provider row, the summary of stored secrets lives here now.
        KeyValueRow(label = stringResource(R.string.settings_keys_stored_label), value = storedProviderIds)
        KeyValueRow(label = stringResource(R.string.settings_keys_hardware_label), value = hardwareBacked)
        if (providers == null) {
            Text(
                text = stringResource(R.string.settings_model_none),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
            return@SectionCard
        }
        Text(
            text = stringResource(R.string.settings_model_providers, providers.entries.size),
            style = MaterialTheme.typography.labelSmall,
            color = chat.muted,
        )
        Text(
            text = stringResource(R.string.settings_model_star_hint, starred.size),
            style = MaterialTheme.typography.labelSmall,
            color = chat.muted,
            modifier = Modifier.semantics { testTag = "settings_star_count" },
        )
        if (model != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onClearModel,
                modifier = Modifier.height(44.dp).semantics { testTag = "model_clear" },
            ) {
                Text(stringResource(R.string.settings_model_clear))
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.settings_providers_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "provider_search" },
        )
        val shown = remember(providers, query) { ProviderCatalog.search(providers.entries, query) }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_providers_no_match, query),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
                modifier = Modifier.semantics { testTag = "provider_search_empty" },
            )
        } else if (query.isNotBlank()) {
            Text(
                text = stringResource(R.string.settings_providers_shown, shown.size, providers.entries.size),
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
                modifier = Modifier.semantics { testTag = "provider_search_count" },
            )
        }
        val starredKeys = remember(starred) { starred.map { ModelRefCodec.encode(it) }.toSet() }
        for (provider in shown) {
            ProviderRow(
                provider = provider,
                connected = providers.connected.contains(provider.id),
                selectedProvider = model?.providerID ?: "",
                selectedModel = model?.modelID ?: "",
                starredKeys = starredKeys,
                onSetModel = onSetModel,
                onToggleStar = onToggleStar,
                onConnect = { connectTarget = provider },
            )
        }
    }

    val target = connectTarget
    if (target != null) {
        ProviderKeyDialog(
            provider = target,
            connected = providers?.connected?.contains(target.id) == true,
            keys = providerKeys(target.id),
            onDismiss = { connectTarget = null },
            onSave = { key ->
                onConnectProvider(target.id, key)
                connectTarget = null
            },
            onRevoke = {
                onRevokeKey(target.id)
                connectTarget = null
            },
            onUseKey = { slot ->
                onUseKey(target.id, slot)
                connectTarget = null
            },
            onDeleteKey = { slot ->
                onDeleteKey(target.id, slot)
                connectTarget = null
            },
        )
    }
}

/**
 * The whole of v4 item 2's one-step activation: a provider the catalog knows needs
 * an API key and nothing else. The base URL and the model list are the catalog's
 * business (the app never asks the user to retype what the agent already published),
 * so this dialog has exactly one field - and it is masked and cleared on save.
 */
@Composable
private fun ProviderKeyDialog(
    provider: OpenCodeApi.ProviderEntry,
    connected: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRevoke: () -> Unit,
    keys: List<ProviderKeyring.Entry> = emptyList(),
    onUseKey: (String) -> Unit = {},
    onDeleteKey: (String) -> Unit = {},
) {
    var key by rememberSaveable(provider.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (connected) R.string.settings_provider_manage_title else R.string.settings_provider_connect_title,
                    provider.name,
                ),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (connected) R.string.settings_provider_manage_body else R.string.settings_provider_connect_body,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                // v6.1 (owner decision): every saved key for this provider, one
                // active. Saving a new key ADDS it (nothing is destroyed any more);
                // "Use" switches manually; the limit-switch does the same hop
                // automatically when the active key runs into a rate/usage limit.
                if (keys.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_keys_stored_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    keys.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .semantics { testTag = "provider_key_row_${entry.slot}" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.label + "  ····" + entry.last4,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.active) {
                                    Text(
                                        text = stringResource(R.string.settings_keys_active),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ChatTheme.chat.success,
                                    )
                                }
                            }
                            if (!entry.active) {
                                val useDesc = stringResource(R.string.settings_keys_use_desc, entry.label, provider.name)
                                TextButton(
                                    onClick = { onUseKey(entry.slot) },
                                    modifier = Modifier.height(36.dp).semantics {
                                        testTag = "provider_key_use_${entry.slot}"
                                        contentDescription = useDesc
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_keys_use))
                                }
                            }
                            val deleteDesc = stringResource(R.string.settings_keys_delete_desc, entry.label, provider.name)
                            TextButton(
                                onClick = { onDeleteKey(entry.slot) },
                                modifier = Modifier.height(36.dp).semantics {
                                    testTag = "provider_key_delete_${entry.slot}"
                                    contentDescription = deleteDesc
                                },
                            ) {
                                Text(stringResource(R.string.settings_keys_delete_one))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_keys_failover_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatTheme.chat.muted,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.settings_keys_field_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "provider_key_value" },
                )
                if (connected) {
                    Spacer(Modifier.height(10.dp))
                    // The manual form's revoke, relocated onto the only place that ever
                    // knows which provider it touches: the connected row itself.
                    // (The description is resolved here, in composable context - a
                    // stringResource call cannot live inside the semantics lambda.)
                    val revokeDesc = stringResource(R.string.settings_provider_revoke_desc, provider.name)
                    TextButton(
                        onClick = onRevoke,
                        modifier = Modifier.height(36.dp).semantics {
                            testTag = "provider_key_revoke"
                            contentDescription = revokeDesc
                        },
                    ) {
                        Text(stringResource(R.string.settings_keys_revoke))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(key.trim()) },
                enabled = key.isNotBlank(),
                modifier = Modifier.semantics { testTag = "provider_key_save" },
            ) {
                Text(
                    // v6.1: with the keyring, saving while connected ADDS a key
                    // (the old label said "Replace", which is exactly what no
                    // longer happens).
                    stringResource(
                        if (connected) R.string.settings_provider_key_add else R.string.settings_keys_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { testTag = "provider_key_cancel" },
            ) {
                Text(stringResource(R.string.settings_provider_connect_cancel))
            }
        },
    )
}

@Composable
private fun ProviderRow(
    provider: OpenCodeApi.ProviderEntry,
    connected: Boolean,
    selectedProvider: String,
    selectedModel: String,
    starredKeys: Set<String> = emptySet(),
    onSetModel: (String, String) -> Unit,
    onToggleStar: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onConnect: (OpenCodeApi.ProviderEntry) -> Unit = {},
) {
    val chat = ChatTheme.chat
    var open by rememberSaveable(provider.id) { mutableStateOf(false) }
    val toggleLabel = provider.name
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        testTag = "provider_${provider.id}"
                        role = Role.Button
                        contentDescription = toggleLabel
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            if (connected) R.string.settings_model_connected else R.string.settings_model_not_connected,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                }
                StatusPill(text = "${provider.models.size}", color = chat.muted)
                if (!connected) {
                    Spacer(Modifier.width(6.dp))
                    // v4 item 2: the key is the only thing asked for, right here.
                    // The visible word is short ("Save key"); the accessible name says
                    // which provider, because a row of identical buttons does not.
                    val connectLabel = stringResource(R.string.settings_provider_connect, provider.name)
                    TextButton(
                        onClick = { onConnect(provider) },
                        modifier = Modifier.height(36.dp).semantics {
                            testTag = "provider_connect_${provider.id}"
                            contentDescription = connectLabel
                        },
                    ) {
                        Text(stringResource(R.string.settings_keys_save))
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                    // v4 item 3: replace and revoke move out of the manual form and onto
                    // the provider they actually belong to. Same dialog, connected mode.
                    val manageLabel = stringResource(R.string.settings_provider_manage_desc, provider.name)
                    TextButton(
                        onClick = { onConnect(provider) },
                        modifier = Modifier.height(36.dp).semantics {
                            testTag = "provider_manage_key_${provider.id}"
                            contentDescription = manageLabel
                        },
                    ) {
                        Text(stringResource(R.string.settings_provider_manage_short))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = chat.muted,
                    modifier = Modifier.size(20.dp).clearAndSetSemantics { },
                )
            }
            if (open) {
                HorizontalDivider(thickness = 1.dp, color = chat.toolBorder)
                ModelList(
                    provider = provider,
                    selectedModel = if (selectedProvider == provider.id) selectedModel else "",
                    starredKeys = starredKeys,
                    onSetModel = onSetModel,
                    onToggleStar = onToggleStar,
                )
            }
        }
    }
}

/** Model list in a bounded scroll box (a provider can list hundreds of models). */
@Composable
private fun ModelList(
    provider: OpenCodeApi.ProviderEntry,
    selectedModel: String,
    starredKeys: Set<String> = emptySet(),
    onSetModel: (String, String) -> Unit,
    onToggleStar: (String, String, Boolean) -> Unit = { _, _, _ -> },
) {
    val chat = ChatTheme.chat
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        for (model in provider.models) {
            val label = stringResource(R.string.settings_model_use, model.name)
            val selected = model.id == selectedModel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { onSetModel(provider.id, model.id) }
                    .semantics {
                        testTag = "model_${provider.id}_${model.id}"
                        role = Role.Button
                        contentDescription = label
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = chat.success,
                        modifier = Modifier.size(16.dp).clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = model.id, style = MonoSmall, color = chat.muted, maxLines = 1)
                }
                if (model.status.isNotBlank()) {
                    StatusPill(text = model.status, color = chat.muted)
                }
                // v4 item 3: "starred" is what the chat header's quick switch shows.
                // It is a checkbox and not a tap on the row because tapping the row
                // already means "use this model now" - two different decisions that
                // must not fight over one gesture.
                val starred = ModelRefCodec.encode(OpenCodeApi.ModelRef(provider.id, model.id)) in starredKeys
                val starLabel = stringResource(
                    if (starred) R.string.settings_model_star_off else R.string.settings_model_star_on,
                    model.name,
                )
                Checkbox(
                    checked = starred,
                    onCheckedChange = { onToggleStar(provider.id, model.id, it) },
                    modifier = Modifier
                        .semantics {
                            testTag = "model_star_${provider.id}_${model.id}"
                            contentDescription = starLabel
                        },
                )
            }
        }
    }
}

// ---- provider keys ---------------------------------------------------------

@Composable
// v4 item 3: the name+key form is gone. Keys come in exactly one way - the
// catalog row's connect chip (or its one-step Manage action when connected) -
// and everything left in this section is the manual custom-provider block.
private fun KeysSection(
    onAddCustomProvider: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
) {
    val chat = ChatTheme.chat
    var customId by rememberSaveable { mutableStateOf("") }
    var customName by rememberSaveable { mutableStateOf("") }
    var customBaseUrl by rememberSaveable { mutableStateOf("") }
    var customModels by rememberSaveable { mutableStateOf("") }
    var customKey by rememberSaveable { mutableStateOf("") }
    SectionCard(
        title = stringResource(R.string.settings_section_keys),
        body = stringResource(R.string.settings_keys_body),
    ) {
        Text(
            text = stringResource(R.string.settings_custom_body),
            style = MaterialTheme.typography.bodySmall,
            color = chat.muted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customId,
            onValueChange = { customId = it },
            label = { Text(stringResource(R.string.settings_custom_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "custom_provider_id" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text(stringResource(R.string.settings_custom_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "custom_provider_name" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customBaseUrl,
            onValueChange = { customBaseUrl = it },
            label = { Text(stringResource(R.string.settings_custom_baseurl)) },
            placeholder = { Text(stringResource(R.string.settings_custom_baseurl)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "custom_provider_baseurl" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customModels,
            onValueChange = { customModels = it },
            label = { Text(stringResource(R.string.settings_custom_models)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "custom_provider_models" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customKey,
            onValueChange = { customKey = it },
            label = { Text(stringResource(R.string.settings_keys_field_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().semantics { testTag = "custom_provider_key" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                onAddCustomProvider(
                    customId.trim(),
                    customName.trim(),
                    customBaseUrl.trim(),
                    customModels,
                    customKey.trim(),
                )
                customKey = ""
            },
            enabled = customId.isNotBlank() && customBaseUrl.isNotBlank() &&
                customModels.isNotBlank() && customKey.isNotBlank(),
            modifier = Modifier.height(46.dp).fillMaxWidth().semantics { testTag = "custom_provider_save" },
        ) {
            Text(stringResource(R.string.settings_custom_save))
        }
    }
}

// ---- MCP -------------------------------------------------------------------

@Composable
private fun McpSection(
    entries: Map<String, OpenCodeApi.McpEntry>,
    onAdd: (String, String, Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val chat = ChatTheme.chat
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var config by rememberSaveable { mutableStateOf("") }
    var persist by rememberSaveable { mutableStateOf(true) }
    SectionCard(
        title = stringResource(R.string.settings_section_mcp),
        body = stringResource(R.string.settings_mcp_body),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.height(44.dp).semantics { testTag = "mcp_refresh" },
            ) {
                Text(stringResource(R.string.settings_mcp_refresh))
            }
            TextButton(
                onClick = { adding = !adding },
                modifier = Modifier.height(44.dp).semantics { testTag = "mcp_add_toggle" },
            ) {
                Text(stringResource(R.string.settings_mcp_add))
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_mcp_empty),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
        }
        for (entry in entries.values) {
            McpRow(entry = entry, onConnect = onConnect, onDisconnect = onDisconnect)
        }
        if (adding) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_mcp_name_label)) },
                placeholder = { Text(stringResource(R.string.settings_mcp_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { testTag = "mcp_name" },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = config,
                onValueChange = { config = it },
                label = { Text(stringResource(R.string.settings_mcp_config_label)) },
                placeholder = { Text(stringResource(R.string.settings_mcp_config_placeholder)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().semantics { testTag = "mcp_config" },
            )
            Spacer(Modifier.height(6.dp))
            val persistLabel = stringResource(R.string.settings_mcp_persist)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { persist = !persist }
                    .semantics {
                        testTag = "mcp_persist"
                        role = Role.Switch
                        contentDescription = persistLabel
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = persistLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = persist,
                    onCheckedChange = { persist = it },
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    onAdd(name.trim(), config.trim(), persist)
                    adding = false
                    name = ""
                    config = ""
                },
                enabled = name.isNotBlank() && config.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(46.dp).semantics { testTag = "mcp_add" },
            ) {
                Text(stringResource(R.string.settings_mcp_add))
            }
        }
    }
}

/** One MCP server: upstream's own status string and, when it failed, its own words. */
@Composable
private fun McpRow(
    entry: OpenCodeApi.McpEntry,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    val statusColor: Color = when (entry.status) {
        "connected" -> chat.success
        "failed" -> MaterialTheme.colorScheme.error
        "needs_auth", "needs_client_registration" -> chat.attention
        else -> chat.muted
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { testTag = "mcp_${entry.name}" },
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // Verbatim: the status string is upstream's, not ours.
                StatusPill(text = entry.status, color = statusColor)
            }
            if (entry.error.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_mcp_error_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                )
                Text(text = entry.error, style = MonoSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onConnect(entry.name) },
                    modifier = Modifier.height(42.dp).semantics { testTag = "mcp_connect_${entry.name}" },
                ) {
                    Text(stringResource(R.string.settings_mcp_connect))
                }
                TextButton(
                    onClick = { onDisconnect(entry.name) },
                    modifier = Modifier.height(42.dp).semantics { testTag = "mcp_disconnect_${entry.name}" },
                ) {
                    Text(stringResource(R.string.settings_mcp_disconnect))
                }
            }
        }
    }
}

// ---- provider setup --------------------------------------------------------

/**
 * "Is a model actually configured", as a first-class labelled state rather than
 * an empty field. Derived only from facts the server and the Keystore already
 * report ([ProviderSetup]); the screen maps it onto copy.
 */
@Composable
private fun ProviderSection(providerSetup: ProviderSetup, connectedCount: Int) {
    val chat = ChatTheme.chat
    val (title, body, color) = when (providerSetup) {
        ProviderSetup.NO_PROVIDER_CONFIGURED -> Triple(
            stringResource(R.string.settings_provider_none_title),
            stringResource(R.string.settings_provider_none_body),
            chat.attention,
        )
        ProviderSetup.PROVIDER_CONFIGURED -> Triple(
            stringResource(R.string.settings_provider_stored_title),
            stringResource(R.string.settings_provider_stored_body),
            chat.attention,
        )
        ProviderSetup.CONNECTED -> Triple(
            stringResource(R.string.settings_provider_connected),
            stringResource(R.string.settings_provider_connected_body, connectedCount),
            chat.success,
        )
        ProviderSetup.UNKNOWN -> Triple(
            stringResource(R.string.settings_provider_checking),
            "",
            chat.muted,
        )
    }
    SectionCard(title = stringResource(R.string.settings_section_provider)) {
        Surface(
            color = if (providerSetup == ProviderSetup.CONNECTED) chat.toolContainer else chat.attentionContainer,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, color),
            modifier = Modifier.fillMaxWidth().semantics { testTag = "provider_setup_${providerSetup.name.lowercase()}" },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (providerSetup == ProviderSetup.CONNECTED) chat.success else chat.onAttentionContainer,
                )
                if (body.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
                    )
                }
            }
        }
    }
}

// ---- permissions -----------------------------------------------------------

/** The permission kinds this screen edits; anything else is left to "ask". */
private val PERMISSION_KEYS = listOf("bash", "edit", "read", "webfetch", "external_directory")

// ---- workspace (v4 items 1 and 4) ------------------------------------------

/**
 * The one place the workspace folder is chosen after the first run.
 *
 * It is here and not on the Files screen because changing it is a configuration
 * decision with a consequence the user has to be told about: the projects in the
 * old folder stop being listed (the app behaves like a terminal that changed
 * directory). Nothing is moved or deleted by the switch itself - [onPick] resolves
 * and probes the folder first, and the move, when the user asks for it, is the
 * explicit "move them" action below.
 */
@Composable
private fun WorkspaceSection(
    path: String,
    visibleToFileManagers: Boolean,
    canGrantAllFilesAccess: Boolean,
    pendingMove: Int,
    onPick: () -> Unit,
    onGrant: () -> Unit,
    onMove: () -> Unit,
) {
    val chat = ChatTheme.chat
    SectionCard(
        title = stringResource(R.string.settings_section_workspace),
        body = stringResource(R.string.settings_workspace_body),
    ) {
        KeyValueRow(
            label = stringResource(R.string.settings_workspace_current),
            value = path.ifEmpty { stringResource(R.string.settings_model_none) },
            mono = path.isNotEmpty(),
        )
        if (!visibleToFileManagers) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_workspace_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
                modifier = Modifier.semantics { testTag = "settings_workspace_hidden" },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.height(46.dp).weight(1f).semantics { testTag = "settings_workspace_pick" },
            ) {
                Text(stringResource(R.string.settings_workspace_pick))
            }
            if (canGrantAllFilesAccess) {
                OutlinedButton(
                    onClick = onGrant,
                    modifier = Modifier.height(46.dp).weight(1f).semantics { testTag = "settings_workspace_grant" },
                ) {
                    Text(stringResource(R.string.settings_workspace_grant))
                }
            }
        }
        if (canGrantAllFilesAccess) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_workspace_access_note),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
        }
        if (pendingMove > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.files_storage_pending, pendingMove),
                style = MaterialTheme.typography.bodySmall,
                color = chat.attention,
                modifier = Modifier.semantics { testTag = "settings_workspace_pending" },
            )
            TextButton(
                onClick = onMove,
                modifier = Modifier.height(44.dp).semantics { testTag = "settings_workspace_move" },
            ) {
                Text(stringResource(R.string.files_storage_move_action))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_workspace_switch_note),
            style = MaterialTheme.typography.bodySmall,
            color = chat.muted,
        )
    }
}

@Composable
private fun PermissionsSection(
    policy: Map<String, String>,
    onPolicy: (String, String) -> Unit,
    onBashPolicy: (String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_section_permissions),
        body = stringResource(R.string.settings_permissions_body),
    ) {
        // A prompt the agent makes while it works always goes through the bottom
        // sheet; this table only sets the standing policy, and it is empty by
        // default, which is exactly OpenCode's own "ask" default.
        for (key in PERMISSION_KEYS) {
            val res = permissionLabelRes(key) ?: continue
            PermissionToolRow(
                label = stringResource(res),
                current = policy[key] ?: "ask",
                onPolicy = onPolicy,
                onBashPolicy = onBashPolicy,
                key = key,
            )
        }
    }
}

@Composable
private fun permissionLabelRes(key: String): Int? = when (key) {
    "bash" -> R.string.settings_permission_bash
    "edit" -> R.string.settings_permission_edit
    "read" -> R.string.settings_permission_read
    "webfetch" -> R.string.settings_permission_webfetch
    "external_directory" -> R.string.settings_permission_external
    else -> null
}

@Composable
private fun permissionValueLabel(value: String): String = stringResource(
    when (value) {
        "allow" -> R.string.settings_permission_value_allow
        "deny" -> R.string.settings_permission_value_deny
        else -> R.string.settings_permission_value_ask
    },
)

@Composable
private fun PermissionToolRow(
    label: String,
    current: String,
    onPolicy: (String, String) -> Unit,
    onBashPolicy: (String) -> Unit,
    key: String,
) {
    val chat = ChatTheme.chat
    Surface(
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = stringResource(R.string.settings_permission_current, permissionValueLabel(current)), color = chat.muted)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyButton(
                    label = stringResource(R.string.settings_permission_value_ask),
                    selected = current == "ask",
                    onClick = {
                        if (key == "bash") onBashPolicy("ask") else onPolicy(key, "ask")
                    },
                    tag = "policy_${key}_ask",
                    modifier = Modifier.weight(1f),
                )
                PolicyButton(
                    label = stringResource(R.string.settings_permission_value_allow),
                    selected = current == "allow",
                    onClick = {
                        if (key == "bash") onBashPolicy("allow") else onPolicy(key, "allow")
                    },
                    tag = "policy_${key}_allow",
                    modifier = Modifier.weight(1f),
                )
                PolicyButton(
                    label = stringResource(R.string.settings_permission_value_deny),
                    selected = current == "deny",
                    onClick = {
                        if (key == "bash") onBashPolicy("deny") else onPolicy(key, "deny")
                    },
                    tag = "policy_${key}_deny",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PolicyButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp).semantics { testTag = tag },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---- memory ----------------------------------------------------------------

/** OpenCode's own persistent memory files, shown and edited in place. */
@Composable
private fun MemorySection(
    memory: MemoryState,
    onSave: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_section_memory),
        body = stringResource(R.string.settings_memory_body),
    ) {
        MemoryEditorCard(
            title = stringResource(R.string.settings_memory_project),
            hint = stringResource(R.string.settings_memory_project_hint),
            content = memory.projectContent,
            hasRules = memory.projectHasRules,
            enabled = memory.projectName.isNotBlank(),
            disabledNote = if (memory.projectName.isBlank()) stringResource(R.string.settings_memory_project_none) else "",
            tagPrefix = "memory_project",
            onSave = { onSave("project", it) },
            onRemove = { onRemove("project") },
        )
        Spacer(Modifier.height(10.dp))
        MemoryEditorCard(
            title = stringResource(R.string.settings_memory_global),
            hint = stringResource(R.string.settings_memory_global_hint),
            content = memory.globalContent,
            hasRules = memory.globalHasRules,
            enabled = true,
            disabledNote = "",
            tagPrefix = "memory_global",
            onSave = { onSave("global", it) },
            onRemove = { onRemove("global") },
        )
    }
}

@Composable
private fun MemoryEditorCard(
    title: String,
    hint: String,
    content: String,
    hasRules: Boolean,
    enabled: Boolean,
    disabledNote: String,
    tagPrefix: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val chat = ChatTheme.chat
    var editing by rememberSaveable(tagPrefix) { mutableStateOf(false) }
    var draft by rememberSaveable(tagPrefix) { mutableStateOf("") }
    Surface(
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(text = hint, style = MaterialTheme.typography.labelSmall, color = chat.muted)
            Spacer(Modifier.height(6.dp))
            if (!enabled) {
                Text(text = disabledNote, style = MaterialTheme.typography.bodySmall, color = chat.muted)
                return@Column
            }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.settings_memory_edit)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "${tagPrefix}_input" },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onSave(draft)
                            editing = false
                        },
                        modifier = Modifier.height(44.dp).semantics { testTag = "${tagPrefix}_save" },
                    ) {
                        Text(stringResource(R.string.settings_memory_save))
                    }
                    TextButton(
                        onClick = { editing = false },
                        modifier = Modifier.height(44.dp).semantics { testTag = "${tagPrefix}_cancel" },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            } else {
                if (content.isNotBlank()) {
                    Text(text = content, style = MaterialTheme.typography.bodySmall, color = chat.muted, maxLines = 6)
                } else {
                    Text(text = stringResource(R.string.settings_memory_empty), style = MaterialTheme.typography.bodySmall, color = chat.muted)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            draft = content
                            editing = true
                        },
                        modifier = Modifier.height(44.dp).semantics { testTag = "${tagPrefix}_edit" },
                    ) {
                        Text(stringResource(R.string.settings_memory_edit))
                    }
                    if (hasRules) {
                        TextButton(
                            onClick = onRemove,
                            modifier = Modifier.height(44.dp).semantics { testTag = "${tagPrefix}_remove" },
                        ) {
                            Text(stringResource(R.string.settings_memory_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    theme: ThemeChoice,
    dynamicColor: Boolean,
    onThemeChange: (ThemeChoice) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_appearance)) {
        Text(
            text = stringResource(R.string.settings_theme_label),
            style = MaterialTheme.typography.labelSmall,
            color = ChatTheme.chat.muted,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeButton(
                label = stringResource(R.string.settings_theme_dark),
                selected = theme == ThemeChoice.DARK,
                onClick = { onThemeChange(ThemeChoice.DARK) },
                tag = "theme_dark",
                modifier = Modifier.weight(1f),
            )
            ThemeButton(
                label = stringResource(R.string.settings_theme_light),
                selected = theme == ThemeChoice.LIGHT,
                onClick = { onThemeChange(ThemeChoice.LIGHT) },
                tag = "theme_light",
                modifier = Modifier.weight(1f),
            )
            ThemeButton(
                label = stringResource(R.string.settings_theme_system),
                selected = theme == ThemeChoice.SYSTEM,
                onClick = { onThemeChange(ThemeChoice.SYSTEM) },
                tag = "theme_system",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        val dynamicLabel = stringResource(R.string.settings_theme_dynamic)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onDynamicColorChange(!dynamicColor) }
                .semantics {
                    testTag = "theme_dynamic"
                    role = Role.Switch
                    contentDescription = dynamicLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = dynamicLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(
                checked = dynamicColor,
                onCheckedChange = onDynamicColorChange,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun ThemeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) ChatTheme.chat.userBubble else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) ChatTheme.chat.attention else ChatTheme.chat.toolBorder),
        modifier = modifier
            .height(46.dp)
            .clickable(onClick = onClick)
            .semantics {
                testTag = tag
                role = Role.RadioButton
                contentDescription = label
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AboutSection(
    appVersion: String,
    serverVersion: String,
    onOpenUrl: (String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_section_about),
        body = stringResource(R.string.settings_about_body),
    ) {
        KeyValueRow(label = stringResource(R.string.settings_about_version), value = appVersion.ifEmpty { "-" }, mono = true)
        // The application ID comes from BuildConfig, so this row cannot drift from
        // what the APK was actually built as. It is the one identifier a user (or a
        // support thread) needs and can never guess from the UI otherwise.
        KeyValueRow(
            label = stringResource(R.string.settings_about_package),
            value = BuildConfig.APPLICATION_ID,
            mono = true,
        )
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_server),
            value = serverVersion.ifEmpty { "-" },
            mono = true,
        )
        // Phase 10 honesty block: this build is an independent client of the
        // upstream project, and it says so on the one screen a curious user or a
        // reviewer opens. It is also where the trademark position is stated in
        // plain words instead of being implied - see docs/BRANDING.md.
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_about_independent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_about_trademark),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(
                label = stringResource(R.string.settings_about_upstream_action),
                url = stringResource(R.string.settings_about_upstream_url),
                tag = "about_upstream",
                onOpenUrl = onOpenUrl,
            )
            LinkButton(
                label = stringResource(R.string.settings_about_privacy_action),
                url = stringResource(R.string.settings_about_privacy_url),
                tag = "about_privacy",
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/**
 * A button that opens an external page.
 *
 * The URL is a parameter, never a literal in this file: the UI layer does not
 * compile URLs (phase6/scripts/check-ui-strings.py), and the label - not the URL -
 * is what a user sees. One helper, so a future link cannot quietly render the raw
 * address into a surface where the welcome/chat gates forbid it.
 */
@Composable
private fun LinkButton(label: String, url: String, tag: String, onOpenUrl: (String) -> Unit) {
    TextButton(
        onClick = { onOpenUrl(url) },
        modifier = Modifier.height(46.dp).semantics { testTag = tag },
    ) {
        Text(label)
    }
}

/**
 * The licences of everything this APK actually contains.
 *
 * Every row is a real component in the shipped artifact, not a formality: the
 * OpenCode bundle, Bun, git and ripgrep are all inside the payload, and the
 * AndroidX/Kotlin libraries are inside the APK. Git is the one GPL-2.0 component,
 * so its written offer is spelled out rather than glossed over
 * (docs/THIRD-PARTY-NOTICES.md carries the pinned sources).
 */
@Composable
private fun OpenSourceSection(onOpenUrl: (String) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_section_opensource),
        body = stringResource(R.string.settings_opensource_body),
    ) {
        KeyValueRow(
            label = stringResource(R.string.settings_opensource_agent),
            value = stringResource(R.string.settings_opensource_agent_value),
            mono = true,
        )
        KeyValueRow(
            label = stringResource(R.string.settings_opensource_components),
            value = stringResource(R.string.settings_opensource_components_value),
            mono = true,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_opensource_gpl_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(
                label = stringResource(R.string.settings_opensource_license_action),
                url = stringResource(R.string.settings_opensource_license_url),
                tag = "opensource_license",
                onOpenUrl = onOpenUrl,
            )
            LinkButton(
                label = stringResource(R.string.settings_opensource_notices_action),
                url = stringResource(R.string.settings_opensource_notices_url),
                tag = "opensource_notices",
                onOpenUrl = onOpenUrl,
            )
        }
    }
}
