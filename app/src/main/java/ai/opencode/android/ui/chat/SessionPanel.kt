package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.Transcript
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.common.relativeTimeLabel
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The conversation list: switching, starting, renaming and deleting sessions.
 *
 * Sessions are upstream objects (`GET /session`): the id, the title the server
 * generated or the user set, its directory and its own timestamps. Nothing here is
 * a client-side notion of a conversation, and the list is a real [LazyColumn] with
 * stable keys because a heavy user accumulates hundreds of them.
 *
 * v9.41 (owner item 15): the row's actions moved behind a three-dot menu -
 * Delete and Rename - and the list became its own composable so the chat's
 * hamburger drawer embeds exactly the same rows (one implementation, one look).
 */
@Composable
fun SessionPanel(
    sessions: List<OpenCodeApi.SessionInfo>,
    selectedSession: String,
    transcript: Transcript.Snapshot,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val newLabel = stringResource(R.string.sessions_new)
    Column(modifier = modifier.fillMaxSize().semantics { testTag = "sessions_screen" }) {
        AppTopBar(
            title = stringResource(R.string.sessions_title),
            subtitle = stringResource(R.string.sessions_count, sessions.size),
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = onNew,
                    modifier = Modifier.semantics { testTag = "session_new" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = newLabel)
                }
            },
        )
        Text(
            text = stringResource(R.string.sessions_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = ChatTheme.chat.muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (sessions.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.sessions_empty_title),
                body = stringResource(R.string.sessions_empty_body),
            )
        } else {
            SessionList(
                sessions = sessions,
                selectedSession = selectedSession,
                transcript = transcript,
                now = now,
                onSelect = onSelect,
                onDelete = onDelete,
                onRename = onRename,
                listTag = "session_list",
            )
        }
    }
}

/**
 * The bare scrollable list of [SessionRow]s, shared by the full panel and the
 * chat's side drawer. [listTag] keeps the panel's gate contract ("session_list")
 * while the drawer stays under its own tag.
 */
@Composable
fun SessionList(
    sessions: List<OpenCodeApi.SessionInfo>,
    selectedSession: String,
    transcript: Transcript.Snapshot,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    listTag: String = "session_list",
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().semantics { testTag = listTag },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                selected = session.id == selectedSession,
                view = transcript.session(session.id),
                now = now,
                onSelect = { onSelect(session.id) },
                onDelete = { onDelete(session.id) },
                onRename = { title -> onRename(session.id, title) },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: OpenCodeApi.SessionInfo,
    selected: Boolean,
    view: Transcript.SessionView?,
    now: Long,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    var editing by rememberSaveable(session.id) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var draft by rememberSaveable(session.id) { mutableStateOf(session.title) }
    val title = session.title.ifBlank { stringResource(R.string.sessions_untitled) }
    val deleteLabel = stringResource(R.string.sessions_delete, title)
    val renameLabel = stringResource(R.string.sessions_rename, title)
    val busy = view?.busy == true
    val waiting = (view?.pending?.size ?: 0) + (view?.questions?.size ?: 0)
    val updated = relativeTimeLabel(session.updatedAt, now)
    val stateLabel = when {
        waiting > 0 -> stringResource(R.string.sessions_pending)
        busy -> stringResource(R.string.sessions_busy)
        else -> ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                testTag = "session_row_${session.id}"
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onSelect),
        color = if (selected) chat.selectedContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) chat.attention else chat.toolBorder),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (updated.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.sessions_updated, updated),
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                        )
                    }
                }
                if (stateLabel.isNotEmpty()) {
                    StatusPill(
                        text = stateLabel,
                        color = if (waiting > 0) chat.attention else chat.success,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // v9.41 (owner item 15): one quiet three-dot affordance; Delete
                // and Rename live in the small menu it opens (Delete first, as
                // the owner listed them). The per-action testTags moved INTO the
                // menu items, so every existing driver keeps its anchors.
                Box {
                    // The semantics builder is NOT a composable context - the label
                    // is read here, where composition is.
                    val menuDesc = stringResource(R.string.sessions_actions, title)
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics {
                            testTag = "session_menu_${session.id}"
                            contentDescription = menuDesc
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = chat.muted,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    ) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sessions_action_delete)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = chat.muted,
                                        modifier = Modifier.clearAndSetSemantics { },
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                                modifier = Modifier.semantics {
                                    testTag = "session_delete_${session.id}"
                                    contentDescription = deleteLabel
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sessions_action_rename)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = null,
                                        tint = chat.muted,
                                        modifier = Modifier.clearAndSetSemantics { },
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    editing = true
                                },
                                modifier = Modifier.semantics {
                                    testTag = "session_rename_${session.id}"
                                    contentDescription = renameLabel
                                },
                            )
                        }
                    }
                }
            }
            if (session.revertMessageID.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.sessions_reverted, session.revertMessageID),
                    style = MonoSmall,
                    color = chat.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.sessions_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onRename(draft)
                            editing = false
                        },
                        modifier = Modifier.height(44.dp),
                    ) {
                        Text(stringResource(R.string.sessions_rename_save))
                    }
                    TextButton(onClick = { editing = false }, modifier = Modifier.height(44.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}
