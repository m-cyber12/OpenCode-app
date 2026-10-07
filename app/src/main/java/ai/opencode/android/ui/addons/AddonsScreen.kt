package ai.opencode.android.ui.addons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import ai.opencode.android.R
import ai.opencode.android.addons.AddonManager
import ai.opencode.android.addons.Addons
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.SectionCard
import ai.opencode.android.ui.theme.ChatTheme

/**
 * v9.24 (owner): the Add-ons surface behind the hamburger menu. Today it
 * holds one add-on - the wasm SWC compiler for Next.js live previews - and
 * the list of what is already downloaded.
 *
 * Owner rules honoured here: the app downloads (no model, no tokens);
 * progress is a DETERMINATE bar when the size is known and plain byte text
 * when it is not (finite animations only); delete is always available.
 */
@Composable
fun AddonsScreen(
    state: AddonManager.State,
    onDownload: (Addons.AddonSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(modifier = modifier.fillMaxSize().semantics { testTag = "addons_screen" }) {
        AppTopBar(title = stringResource(R.string.addons_title), onBack = onBack)
        val sections = listOf("catalog", "installed")
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { testTag = "addons_list" },
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items = sections, key = { it }) { section ->
                when (section) {
                    "catalog" -> SectionCard(
                    title = stringResource(R.string.addons_wasm_title),
                    body = stringResource(R.string.addons_wasm_body),
                ) {
                    val spec = Addons.CATALOG.first()
                    val busy = state.phase == AddonManager.Phase.RESOLVING ||
                        state.phase == AddonManager.Phase.DOWNLOADING
                    val alreadyInstalled = state.installed.any { it.fileName.startsWith(spec.id + "-") }
                    when (state.phase) {
                        AddonManager.Phase.DOWNLOADING -> {
                            val received = state.receivedBytes / (1024 * 1024)
                            if (state.totalBytes > 0) {
                                val total = state.totalBytes / (1024 * 1024)
                                LinearProgressIndicator(
                                    progress = {
                                        (state.receivedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth().semantics { testTag = "addon_progress" },
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.addons_progress, received, total),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = chat.muted,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.addons_progress_unknown, received),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = chat.muted,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.fillMaxWidth().semantics { testTag = "addon_cancel" },
                            ) {
                                Text(stringResource(R.string.addons_cancel))
                            }
                        }
                        AddonManager.Phase.RESOLVING -> {
                            Text(
                                text = stringResource(R.string.addons_resolving),
                                style = MaterialTheme.typography.bodySmall,
                                color = chat.muted,
                            )
                        }
                        else -> {
                            if (state.phase == AddonManager.Phase.ERROR) {
                                Text(
                                    text = stringResource(R.string.addons_error, state.error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (alreadyInstalled) {
                                Text(
                                    text = stringResource(R.string.addons_installed_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = chat.muted,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedButton(
                                onClick = { onDownload(spec) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().semantics { testTag = "addon_add_wasm" },
                            ) {
                                Text(
                                    stringResource(
                                        if (state.phase == AddonManager.Phase.ERROR) {
                                            R.string.addons_retry
                                        } else {
                                            R.string.addons_add
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                    "installed" -> SectionCard(
                    title = stringResource(R.string.addons_installed_title),
                    body = stringResource(R.string.addons_installed_body),
                ) {
                    if (state.installed.isEmpty()) {
                        Text(
                            text = stringResource(R.string.addons_installed_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = chat.muted,
                        )
                    }
                    for (file in state.installed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = file.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = stringResource(R.string.addons_size_mb, file.sizeBytes / (1024 * 1024)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chat.muted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { onDelete(file.fileName) }) {
                                Text(stringResource(R.string.addons_delete))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                }
            }
        }
    }
}
