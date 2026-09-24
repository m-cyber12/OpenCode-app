package ai.opencode.android.ui.projects

import ai.opencode.android.R
import ai.opencode.android.projects.Project
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.SectionCard
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One session (chat) inside a project, as the server lists it. */
data class ProjectSession(val id: String, val title: String, val updatedAtMs: Long)

/** A workspace root the app can switch to. [current] is the one it uses now. */
data class KnownWorkspace(val path: String, val projectCount: Int, val current: Boolean)

/**
 * The page's list, flattened: the expanded project's conversations are REAL lazy
 * items of the one LazyColumn, not an eager loop inside a row - a project can
 * hold arbitrarily many sessions, and phase 6's list rule (check-ui-lists) is
 * exactly about collections like that.
 */
private sealed interface PageRow {
    val key: String
    data class Header(val project: Project) : PageRow {
        override val key get() = "p:" + project.name
    }
    data class NoSessions(val projectName: String) : PageRow {
        override val key get() = "e:" + projectName
    }
    data class OneSession(val projectName: String, val session: ProjectSession) : PageRow {
        override val key get() = "s:" + session.id
    }
    data class NewSession(val projectName: String) : PageRow {
        override val key get() = "n:" + projectName
    }
}

/**
 * Choosing, creating and managing the folders the agent works in.
 *
 * Phase 6 kept this to list, create, open. Phase 7 adds the rest of workspace
 * management: import a folder from outside the app (via the system picker),
 * export a project as a zip, rename, and delete. Every project lives under the
 * app's own storage ([ProjectStore]) and the server scopes sessions by that
 * directory, so "project" here means exactly what it means to OpenCode - and
 * nothing here exposes the rest of the Android filesystem to the agent.
 *
 * Phase 10 continuation v6: the page now shows the workspace it is a listing OF.
 * The folder sits at the top with a Switch action (and the small import action);
 * only that folder's projects are listed, as expandable rows - expanding reveals
 * the project's sessions with New/Rename/Delete actions; a collapsed History
 * section keeps previously used folders discoverable (tap switches back) so a
 * switch never looks like data loss. Project-level rename/files/export/delete
 * stay in the three-dot menu, as they were.
 *
 * The screen stays a pure function of its parameters: every management action is
 * a callback the caller (AppRoot) wires to [ProjectStore] and the SAF transfer.
 */
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    activeName: String,
    runtimeLine: String,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    workspacePath: String = "",
    workspaces: List<KnownWorkspace> = emptyList(),
    onSwitchTo: (String) -> Unit = {},
    onBrowseWorkspace: () -> Unit = {},
    sessions: Map<String, List<ProjectSession>> = emptyMap(),
    expandedName: String = "",
    onToggleExpand: (String) -> Unit = {},
    onSelect: (String) -> Unit = {},
    onOpenSession: (String, String) -> Unit = { _, _ -> },
    onNewSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onDeleteSession: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onExport: (String) -> Unit = {},
    onFiles: (String) -> Unit = {},
    importing: Boolean = false,
    importError: String = "",
    /**
     * Something the user should know about the project *location* (Phase 10
     * continuation v3: a storage change moved their projects, or could not). Shown
     * on this screen, not only on the Files screen, because this is the screen the
     * app opens on - a move the user never sees is a move that looks like data loss.
     */
    notice: String = "",
    sessionCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var sessionDeleteTarget by remember { mutableStateOf<ProjectSession?>(null) }
    var sessionRenameTarget by remember { mutableStateOf<ProjectSession?>(null) }
    var sessionRenameText by rememberSaveable { mutableStateOf("") }
    var switchOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().semantics { testTag = "projects_screen" }) {
        AppTopBar(
            title = stringResource(R.string.projects_title),
            subtitle = runtimeLine,
            onBack = onBack,
        )
        WorkspaceCard(
            workspacePath = workspacePath,
            rootCount = workspaces.size,
            onSwitchOpen = { switchOpen = true },
            onImport = onImport,
            importing = importing,
        )
        CreateProjectCard(onCreate = onCreate, existing = projects.map { it.name })
        if (importError.isNotBlank()) {
            Text(
                text = stringResource(R.string.projects_import_failed, importError),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (notice.isNotBlank()) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = ChatTheme.chat.attention,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .semantics { testTag = "projects_notice" },
            )
        }
        val pageRows = remember(projects, expandedName, sessions) {
            buildList {
                for (project in projects) {
                    add(PageRow.Header(project))
                    if (project.name == expandedName) {
                        val list = sessions[project.name].orEmpty()
                        if (list.isEmpty()) add(PageRow.NoSessions(project.name))
                        for (sess in list) add(PageRow.OneSession(project.name, sess))
                        add(PageRow.NewSession(project.name))
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { testTag = "project_list" },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (projects.isEmpty()) {
                item(key = "projects_empty") {
                    EmptyState(
                        title = stringResource(R.string.projects_empty_title),
                        body = stringResource(R.string.projects_empty_body),
                    )
                }
            }
            items(items = pageRows, key = { it.key }) { row ->
                when (row) {
                    is PageRow.Header -> ProjectRow(
                        project = row.project,
                        active = row.project.name == activeName,
                        expanded = row.project.name == expandedName,
                        sessionCount = sessionCounts[row.project.name]
                            ?: (sessions[row.project.name]?.size ?: 0),
                        now = now,
                        onToggle = { onToggleExpand(row.project.name) },
                        onSelect = { onSelect(row.project.name) },
                        onRename = {
                            renameText = row.project.name
                            renameTarget = row.project
                        },
                        onDelete = { deleteTarget = row.project },
                        onExport = { onExport(row.project.name) },
                        onFiles = { onFiles(row.project.name) },
                    )
                    is PageRow.NoSessions -> Text(
                        text = stringResource(R.string.projects_sessions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatTheme.chat.muted,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                    )
                    is PageRow.OneSession -> SessionRow(
                        session = row.session,
                        now = now,
                        onOpen = { onOpenSession(row.projectName, row.session.id) },
                        onRename = {
                            sessionRenameText = row.session.title
                            sessionRenameTarget = row.session
                        },
                        onDelete = { sessionDeleteTarget = row.session },
                    )
                    is PageRow.NewSession -> NewSessionButton(
                        projectName = row.projectName,
                        onNewSession = { onNewSession(row.projectName) },
                    )
                }
            }
            val previous = workspaces.filterNot { it.current }
            if (previous.isNotEmpty()) {
                item(key = "projects_history") {
                    HistorySection(workspaces = previous, onSwitchTo = onSwitchTo)
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            project = target,
            name = renameText,
            onNameChange = { renameText = it },
            onConfirm = {
                onRename(target.name, renameText)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.projects_delete_title, target.name)) },
            text = { Text(stringResource(R.string.projects_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.name)
                        deleteTarget = null
                    },
                    modifier = Modifier.semantics { testTag = "project_delete_confirm" },
                ) {
                    Text(stringResource(R.string.projects_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    sessionRenameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionRenameTarget = null },
            title = { Text(stringResource(R.string.projects_session_rename)) },
            text = {
                OutlinedTextField(
                    value = sessionRenameText,
                    onValueChange = { sessionRenameText = it },
                    label = { Text(stringResource(R.string.projects_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "session_rename_input" },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(target.id, sessionRenameText)
                        sessionRenameTarget = null
                    },
                    enabled = sessionRenameText.isNotBlank(),
                    modifier = Modifier.semantics { testTag = "session_rename_save" },
                ) { Text(stringResource(R.string.projects_rename_save)) }
            },
            dismissButton = {
                TextButton(onClick = { sessionRenameTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    sessionDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionDeleteTarget = null },
            title = { Text(stringResource(R.string.projects_session_delete_title, activeName)) },
            text = { Text(stringResource(R.string.projects_session_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(target.id)
                        sessionDeleteTarget = null
                    },
                    modifier = Modifier.semantics { testTag = "session_delete_confirm" },
                ) {
                    Text(stringResource(R.string.projects_session_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionDeleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (switchOpen) {
        SwitchWorkspaceDialog(
            workspaces = workspaces,
            onPick = { path ->
                switchOpen = false
                onSwitchTo(path)
            },
            onBrowse = {
                switchOpen = false
                onBrowseWorkspace()
            },
            onDismiss = { switchOpen = false },
        )
    }
}

@Composable
private fun RenameDialog(
    project: Project,
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_rename)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.projects_folder) + ": " + project.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatTheme.chat.muted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.projects_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "project_rename_input" },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                modifier = Modifier.semantics { testTag = "project_rename_save" },
            ) {
                Text(stringResource(R.string.projects_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CreateProjectCard(
    onCreate: (String) -> Unit,
    existing: List<String>,
) {
    var name by rememberSaveable { mutableStateOf("") }
    // ProjectStore.sanitize is the pure name rule (no storage access), used here so
    // the hint matches what create() will actually do with the typed name.
    val taken = name.isNotBlank() && existing.contains(ProjectStore.sanitize(name))
    SectionCard(
        title = stringResource(R.string.projects_new),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.projects_name_label)) },
            placeholder = { Text(stringResource(R.string.projects_name_placeholder)) },
            supportingText = { Text(stringResource(R.string.projects_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { testTag = "project_name_input" },
        )
        if (taken) {
            Text(
                text = stringResource(R.string.projects_name_taken),
                style = MaterialTheme.typography.labelSmall,
                color = ChatTheme.chat.attention,
            )
            Spacer(Modifier.height(4.dp))
        }
        Button(
            onClick = {
                onCreate(name)
                name = ""
            },
            modifier = Modifier.fillMaxWidth().height(50.dp).semantics { testTag = "project_create" },
        ) {
            Text(stringResource(R.string.projects_create), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    active: Boolean,
    expanded: Boolean,
    sessionCount: Int,
    now: Long,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onFiles: () -> Unit,
) {
    val chat = ChatTheme.chat
    val opened = relativeTimeLabel(project.lastOpenedMs, now)
    val menuLabel = stringResource(R.string.projects_menu, project.name)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (active) chat.userBubble else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (active) chat.attention else chat.toolBorder),
    ) {
        Column {
            // Tapping the row expands/collapses the sessions under this project;
            // opening to the chat is the session's job (or "+ New session").
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable {
                        if (!expanded) onSelect()
                        onToggle()
                    }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
                    .semantics {
                        testTag = "project_row_${project.name}"
                        role = Role.Button
                        contentDescription = project.name
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (opened.isEmpty()) {
                            stringResource(R.string.projects_never_opened)
                        } else {
                            stringResource(R.string.projects_last_opened, opened)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                        maxLines = 1,
                    )
                }
                if (active) {
                    StatusPill(text = stringResource(R.string.projects_current), color = chat.success)
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(text = "$sessionCount", color = chat.muted)
                Spacer(Modifier.width(2.dp))
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics {
                            testTag = "project_menu_${project.name}"
                            contentDescription = menuLabel
                        },
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).clearAndSetSemantics { },
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.projects_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                            modifier = Modifier.semantics { testTag = "project_rename_${project.name}" },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.projects_files)) },
                            onClick = {
                                menuOpen = false
                                onFiles()
                            },
                            modifier = Modifier.semantics { testTag = "project_files_${project.name}" },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.projects_export)) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                            modifier = Modifier.semantics { testTag = "project_export_${project.name}" },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.projects_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                            modifier = Modifier.semantics { testTag = "project_delete_${project.name}" },
                        )
                    }
                }
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = chat.muted,
                    modifier = Modifier
                        .width(20.dp)
                        .clearAndSetSemantics { },
                )
            }
            if (expanded) {
                HorizontalDivider(thickness = 1.dp, color = chat.toolBorder)
                // Only the (bounded) path lives inside the row; the sessions are
                // the LazyColumn's own items, right below this header.
                Text(
                    text = project.path,
                    style = MonoSmall,
                    color = chat.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

/** The expanded project's way into a fresh conversation - its own lazy item. */
@Composable
private fun NewSessionButton(
    projectName: String,
    onNewSession: () -> Unit,
) {
    val newSessionLabel = stringResource(R.string.projects_session_new)
    OutlinedButton(
        onClick = onNewSession,
        modifier = Modifier
            .padding(start = 20.dp)
            .height(40.dp)
            .semantics {
                testTag = "projects_new_session_${projectName}"
                contentDescription = newSessionLabel
            },
    ) {
        Text(newSessionLabel)
    }
}

@Composable
private fun SessionRow(
    session: ProjectSession,
    now: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val chat = ChatTheme.chat
    val title = session.title.ifEmpty { session.id }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .heightIn(min = 44.dp)
            .clickable(onClick = onOpen)
            .semantics {
                testTag = "projects_session_${session.id}"
                role = Role.Button
                contentDescription = title
            }
            .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val updated = relativeTimeLabel(session.updatedAtMs, now)
            if (updated.isNotEmpty()) {
                Text(
                    text = updated,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                )
            }
        }
        TextButton(
            onClick = onRename,
            modifier = Modifier.height(36.dp).semantics { testTag = "session_rename_${session.id}" },
        ) {
            Text(stringResource(R.string.projects_session_rename), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.height(36.dp).semantics { testTag = "session_delete_${session.id}" },
        ) {
            Text(
                stringResource(R.string.projects_session_delete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Where the listing lives: the workspace folder itself, the action that switches
 * it, and the (small) import action. Every speech- and touch-path names what the
 * path is; nothing here is only visual.
 */
@Composable
private fun WorkspaceCard(
    workspacePath: String,
    rootCount: Int,
    onSwitchOpen: () -> Unit,
    onImport: () -> Unit,
    importing: Boolean,
) {
    val chat = ChatTheme.chat
    SectionCard(
        title = stringResource(R.string.projects_workspace_label),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = workspacePath,
            style = MonoSmall,
            color = chat.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "projects_workspace_path" },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val switchDesc = stringResource(R.string.projects_workspace_switch_desc)
            OutlinedButton(
                onClick = onSwitchOpen,
                modifier = Modifier.height(42.dp).weight(1f).semantics {
                    testTag = "projects_switch_workspace"
                    contentDescription = switchDesc
                },
            ) {
                Text(
                    stringResource(R.string.projects_workspace_switch) +
                        if (rootCount > 1) " ($rootCount)" else "",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.height(42.dp).semantics { testTag = "project_import" },
            ) {
                Text(
                    text = stringResource(if (importing) R.string.projects_importing else R.string.projects_import),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The folders the user has worked in before. Collapsed by default (one line),
 * expanded it is a plain list of roots with their project counts; tapping a root
 * switches to it (the server never moves anything - cf `cd`).
 */
@Composable
private fun HistorySection(
    workspaces: List<KnownWorkspace>,
    onSwitchTo: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        testTag = "projects_history"
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.projects_history, workspaces.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = chat.muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (open) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = chat.muted,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            if (open) {
                HorizontalDivider(thickness = 1.dp, color = chat.toolBorder)
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.projects_history_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    workspaces.forEach { ws ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable { onSwitchTo(ws.path) }
                                .padding(vertical = 6.dp)
                                .semantics {
                                    testTag = "projects_history_root"
                                    role = Role.Button
                                    contentDescription = ws.path
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ws.path,
                                    style = MonoSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.projects_history_project_count, ws.projectCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chat.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The remembered-roots list plus the full "browse for a folder" entry. */
@Composable
private fun SwitchWorkspaceDialog(
    workspaces: List<KnownWorkspace>,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chat = ChatTheme.chat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_switch_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.projects_switch_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                )
                Spacer(Modifier.height(10.dp))
                workspaces.forEach { ws ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable(enabled = !ws.current) { onPick(ws.path) }
                            .padding(vertical = 6.dp)
                            .semantics {
                                testTag = if (ws.current) "projects_switch_current" else "projects_switch_root"
                                if (!ws.current) role = Role.Button
                                contentDescription = ws.path
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = ws.path,
                                style = MonoSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (ws.current) {
                                    stringResource(R.string.projects_switch_current)
                                } else {
                                    stringResource(R.string.projects_history_project_count, ws.projectCount)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ws.current) chat.success else chat.muted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onBrowse,
                    modifier = Modifier.fillMaxWidth().height(44.dp).semantics { testTag = "projects_switch_browse" },
                ) {
                    Text(stringResource(R.string.projects_switch_browse))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
