package ai.opencode.android.ui.changes

import ai.opencode.android.R
import ai.opencode.android.client.WorkLog
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.ProjectTabs
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.common.relativeTimeLabel
import ai.opencode.android.ui.markdown.CodeBlock
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * v7: the CHANGES surface - what the agent did to the files, stage by stage.
 *
 * Each assistant turn that touched a file is one stage card: when it ran, which
 * files it wrote or edited, the per-file +/- counts the server computed, and the
 * patch itself one tap away. Newest stage first, because the question this page
 * answers is "what just changed".
 *
 * Pure function of [WorkLog.TurnChanges] + callbacks, like every other screen -
 * no repository, no runtime, so the Compose gates can render it from fabricated
 * state.
 */
@Composable
fun ChangesScreen(
    projectName: String,
    turns: List<WorkLog.TurnChanges>,
    onBack: () -> Unit,
    onSelectTab: (ProjectTab) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    Column(modifier.fillMaxSize().semantics { testTag = "changes_screen" }) {
        AppTopBar(
            title = stringResource(R.string.changes_title),
            subtitle = projectName,
            onBack = onBack,
        )
        if (turns.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.changes_empty_title),
                body = stringResource(R.string.changes_empty_body),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                items(
                    count = turns.size,
                    key = { turns[it].messageID },
                ) { index ->
                    TurnCard(turns[index], now)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        // v8: the surfaces live in a bottom bar now - same tags as the v7 strip.
        ProjectTabs(current = ProjectTab.CHANGES, onSelect = onSelectTab)
    }
}

@Composable
private fun TurnCard(turn: WorkLog.TurnChanges, now: Long) {
    val chat = ChatTheme.chat
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "changes_turn_${turn.messageID}" },
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.changes_turn_label, turn.turnIndex),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (turn.atMs > 0) {
                    Text(
                        text = relativeTimeLabel(turn.atMs, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    text = stringResource(R.string.changes_turn_files, turn.changes.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                )
                Spacer(Modifier.width(10.dp))
                PlusMinus(turn.additions, turn.deletions)
            }
            Spacer(Modifier.height(8.dp))
            Column {
                for (i in turn.changes.indices) {
                    ChangeRow(turn.changes[i])
                    if (i != turn.changes.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: WorkLog.Change) {
    val chat = ChatTheme.chat
    var expanded by rememberSaveable(change.partId) { mutableStateOf(false) }
    val hasPatch = change.patch.isNotBlank()
    val toggleLabel = stringResource(
        if (expanded) R.string.chat_tool_collapse else R.string.chat_tool_expand,
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(enabled = hasPatch) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics {
                        testTag = "changes_row_${change.partId}"
                        if (hasPatch) {
                            role = Role.Button
                            contentDescription = toggleLabel
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = change.file.ifBlank { stringResource(R.string.changes_unknown_file) },
                        style = MonoSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = change.tool,
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (change.status == "error") {
                    StatusPill(
                        text = stringResource(R.string.chat_tool_status_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                PlusMinus(change.additions, change.deletions)
            }
            if (expanded && hasPatch) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    CodeBlock(language = "diff", source = change.patch)
                }
            }
        }
    }
}

/** "+12 -3", coloured, always both signs so the shape of the row is stable. */
@Composable
private fun PlusMinus(additions: Int, deletions: Int) {
    val chat = ChatTheme.chat
    Row {
        Text(
            text = stringResource(R.string.changes_additions, additions),
            style = MonoSmall,
            color = chat.success,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.changes_deletions, deletions),
            style = MonoSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
