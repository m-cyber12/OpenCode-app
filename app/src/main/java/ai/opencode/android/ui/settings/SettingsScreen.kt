package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.ui.common.DetailDisclosure
import ai.opencode.android.ui.common.KeyValueRow
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.SectionCard
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.common.ThemeChoice
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.availabilityHeadline
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
    onAddMcp: (String, String, Boolean) -> Unit,
    onConnectMcp: (String) -> Unit,
    onDisconnectMcp: (String) -> Unit,
    onRefreshMcp: () -> Unit,
    onBashPolicy: (String) -> Unit,
    onShareDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
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
        hardwareBacked, appVersion, theme, dynamicColor,
    ) {
        listOf(
            "runtime",
            "diagnostics",
            "model",
            "keys",
            "mcp",
            "permissions",
            "appearance",
            "about",
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
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = sections, key = { it }) { section ->
                when (section) {
                    "runtime" -> RuntimeSection(
                        runtime = runtime,
                        state = state,
                        availability = availability,
                        onRestart = onRestartRuntime,
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
                        onSetModel = onSetModel,
                        onClearModel = onClearModel,
                    )
                    "keys" -> KeysSection(
                        providers = state.providers,
                        storedProviderIds = storedProviderIds,
                        hardwareBacked = hardwareBacked,
                        onSaveKey = onSaveKey,
                        onRevokeKey = onRevokeKey,
                    )
                    "mcp" -> McpSection(
                        entries = state.mcp,
                        onAdd = onAddMcp,
                        onConnect = onConnectMcp,
                        onDisconnect = onDisconnectMcp,
                        onRefresh = onRefreshMcp,
                    )
                    "permissions" -> PermissionsSection(onBashPolicy = onBashPolicy)
                    "appearance" -> AppearanceSection(
                        theme = theme,
                        dynamicColor = dynamicColor,
                        onThemeChange = onThemeChange,
                        onDynamicColorChange = onDynamicColorChange,
                    )
                    else -> AboutSection(appVersion = appVersion, serverVersion = state.serverVersion)
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
    onSetModel: (String, String) -> Unit,
    onClearModel: () -> Unit,
) {
    val chat = ChatTheme.chat
    SectionCard(title = stringResource(R.string.settings_section_model)) {
        val current = if (model == null) "" else "${model.providerID}/${model.modelID}"
        KeyValueRow(
            label = stringResource(R.string.settings_model_current),
            value = current.ifEmpty { stringResource(R.string.settings_model_none) },
            mono = current.isNotEmpty(),
        )
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
        for (provider in providers.entries) {
            ProviderRow(
                provider = provider,
                connected = providers.connected.contains(provider.id),
                selectedProvider = model?.providerID ?: "",
                selectedModel = model?.modelID ?: "",
                onSetModel = onSetModel,
            )
        }
    }
}

@Composable
private fun ProviderRow(
    provider: OpenCodeApi.ProviderEntry,
    connected: Boolean,
    selectedProvider: String,
    selectedModel: String,
    onSetModel: (String, String) -> Unit,
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
                    onSetModel = onSetModel,
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
    onSetModel: (String, String) -> Unit,
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
            }
        }
    }
}

// ---- provider keys ---------------------------------------------------------

@Composable
private fun KeysSection(
    providers: OpenCodeApi.ProviderSnapshot?,
    storedProviderIds: String,
    hardwareBacked: String,
    onSaveKey: (String, String) -> Unit,
    onRevokeKey: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    var providerId by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    SectionCard(
        title = stringResource(R.string.settings_section_keys),
        body = stringResource(R.string.settings_keys_body),
    ) {
        Text(
            text = stringResource(R.string.settings_keys_optional),
            style = MaterialTheme.typography.bodySmall,
            color = chat.muted,
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow(label = stringResource(R.string.settings_keys_stored_label), value = storedProviderIds)
        KeyValueRow(label = stringResource(R.string.settings_keys_hardware_label), value = hardwareBacked)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = providerId,
            onValueChange = { providerId = it },
            label = { Text(stringResource(R.string.settings_keys_provider)) },
            placeholder = { Text(providers?.allIds?.firstOrNull() ?: "anthropic") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "key_provider" },
        )
        Spacer(Modifier.height(8.dp))
        // Masked, never read back: the field only ever holds what the user typed in
        // this session, and saving clears it.
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.settings_keys_field_label)) },
            placeholder = { Text(stringResource(R.string.settings_keys_field_placeholder)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().semantics { testTag = "key_value" },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onSaveKey(providerId.trim(), apiKey.trim())
                    apiKey = ""
                },
                enabled = providerId.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.height(46.dp).weight(1f).semantics { testTag = "key_save" },
            ) {
                Text(stringResource(R.string.settings_keys_save))
            }
            OutlinedButton(
                onClick = {
                    onRevokeKey(providerId.trim())
                    apiKey = ""
                },
                enabled = providerId.isNotBlank(),
                modifier = Modifier.height(46.dp).weight(1f).semantics { testTag = "key_revoke" },
            ) {
                Text(stringResource(R.string.settings_keys_revoke))
            }
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

// ---- permissions / appearance / about --------------------------------------

@Composable
private fun PermissionsSection(onBashPolicy: (String) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_section_permissions),
        body = stringResource(R.string.settings_permissions_body),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PolicyButton(
                label = stringResource(R.string.settings_permission_ask),
                policy = "ask",
                onBashPolicy = onBashPolicy,
                modifier = Modifier.weight(1f),
            )
            PolicyButton(
                label = stringResource(R.string.settings_permission_allow),
                policy = "allow",
                onBashPolicy = onBashPolicy,
                modifier = Modifier.weight(1f),
            )
            PolicyButton(
                label = stringResource(R.string.settings_permission_deny),
                policy = "deny",
                onBashPolicy = onBashPolicy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PolicyButton(
    label: String,
    policy: String,
    onBashPolicy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onBashPolicy(policy) },
        modifier = modifier.height(46.dp).semantics { testTag = "policy_$policy" },
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
private fun AboutSection(appVersion: String, serverVersion: String) {
    SectionCard(
        title = stringResource(R.string.settings_section_about),
        body = stringResource(R.string.settings_about_body),
    ) {
        KeyValueRow(label = stringResource(R.string.settings_about_version), value = appVersion.ifEmpty { "-" }, mono = true)
        KeyValueRow(
            label = stringResource(R.string.settings_runtime_server),
            value = serverVersion.ifEmpty { "-" },
            mono = true,
        )
    }
}
