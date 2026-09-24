package ai.opencode.android.ui.terminal

import ai.opencode.android.R
import ai.opencode.android.client.WorkLog
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.ProjectTabs
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v7: the TERMINAL surface - every shell command the agent ran in this
 * conversation, as a console. `$ command` in the accent colour, captured output
 * below it, exit code when the server reported one. Oldest first, because a
 * terminal scrolls down; the list starts pinned to the end.
 *
 * Read-only on purpose (the owner's brief: agent commands and logs are enough).
 * The full tool cards in the chat remain the interactive record; this page is
 * the uncluttered console view of the same server state.
 */
@Composable
fun TerminalScreen(
    projectName: String,
    commands: List<WorkLog.Command>,
    onBack: () -> Unit,
    onSelectTab: (ProjectTab) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The runtime's own log tail (the same lines the Settings diagnostics view
     * shows) - the owner's brief for this page is "agent commands and logs".
     * Collapsed by default: the commands are the page, the log is the appendix.
     */
    runtimeLog: List<String> = emptyList(),
) {
    Column(modifier.fillMaxSize().semantics { testTag = "terminal_screen" }) {
        AppTopBar(
            title = stringResource(R.string.terminal_title),
            subtitle = projectName,
            onBack = onBack,
        )
        ProjectTabs(current = ProjectTab.TERMINAL, onSelect = onSelectTab)
        if (runtimeLog.isNotEmpty()) {
            RuntimeLogSection(runtimeLog)
        }
        if (commands.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.terminal_empty_title),
                body = stringResource(R.string.terminal_empty_body),
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                color = ChatTheme.code.blockBackground,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    reverseLayout = true,
                ) {
                    // reverseLayout pins the newest entry to the bottom edge like a
                    // real console, so item 0 is the LAST command.
                    items(
                        count = commands.size,
                        key = { commands[commands.size - 1 - it].partId },
                    ) { reversed ->
                        CommandEntry(commands[commands.size - 1 - reversed])
                    }
                }
            }
        }
    }
}

/**
 * The runtime log tail, one tap away. The last lines lead (that is where a crash
 * or a restart shows), and the box is height-capped so the console below stays
 * the page's main surface.
 */
@Composable
private fun RuntimeLogSection(lines: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (open) R.string.terminal_log_hide else R.string.terminal_log_show,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.semantics {
                testTag = "terminal_log_toggle"
                contentDescription = toggleLabel
            },
        ) {
            Text(
                text = stringResource(R.string.terminal_log_title),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (open) {
            Surface(
                color = ChatTheme.code.blockBackground,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = lines.takeLast(120).joinToString("\n"),
                    style = MonoSmall,
                    color = ChatTheme.chat.muted,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                        .semantics { testTag = "terminal_log" },
                )
            }
        }
    }
}

@Composable
private fun CommandEntry(command: WorkLog.Command) {
    val chat = ChatTheme.chat
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { testTag = "terminal_row_${command.partId}" },
    ) {
        if (command.description.isNotBlank()) {
            Text(
                text = command.description,
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
            )
            Spacer(Modifier.height(2.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.terminal_prompt_symbol),
                style = MonoSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = command.command,
                style = MonoSmall,
                fontWeight = FontWeight.Medium,
            )
        }
        if (command.output.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = command.output,
                style = MonoSmall,
                color = chat.muted,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(3.dp))
        val footer = when {
            command.status == "running" -> stringResource(R.string.terminal_running)
            command.status == "error" -> stringResource(R.string.terminal_failed)
            command.exit != null -> stringResource(R.string.terminal_exit, command.exit)
            else -> ""
        }
        if (footer.isNotEmpty()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = when (command.status) {
                    "error" -> MaterialTheme.colorScheme.error
                    "running" -> MaterialTheme.colorScheme.primary
                    else -> chat.success
                },
            )
        }
    }
}
