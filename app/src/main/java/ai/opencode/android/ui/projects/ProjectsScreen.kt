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
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onExport: (String) -> Unit = {},
    importing: Boolean = false,
    importError: String = "",
    sessionCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    Column(modifier = modifier.fillMaxSize().semantics { testTag = "projects_screen" }) {
        AppTopBar(
            title = stringResource(R.string.projects_title),
            subtitle = runtimeLine,
            onBack = onBack,
        )
        CreateProjectCard(onCreate = onCreate, onImport = onImport, importing = importing, existing = projects.map { it.name })
        if (importError.isNotBlank()) {
            Text(
                text = stringResource(R.string.projects_import_failed, importError),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { testTag = "project_list" },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "projects_intro") {
                Text(
                    text = stringResource(R.string.projects_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatTheme.chat.muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            if (projects.isEmpty()) {
                item(key = "projects_empty") {
                    EmptyState(
                        title = stringResource(R.string.projects_empty_title),
                        body = stringResource(R.string.projects_empty_body),
                    )
                }
            }
            items(items = projects, key = { it.name }) { project ->
                ProjectRow(
                    project = project,
                    active = project.name == activeName,
                    sessionCount = sessionCounts[project.name] ?: 0,
                    now = now,
                    onOpen = { onOpen(project.name) },
                    onRename = {
                        renameText = project.name
                        renameTarget = project
                    },
                    onDelete = { deleteTarget = project },
                    onExport = { onExport(project.name) },
                )
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
    onImport: () -> Unit,
    importing: Boolean,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onCreate(name)
                    name = ""
                },
                modifier = Modifier.weight(1f).height(50.dp).semantics { testTag = "project_create" },
            ) {
                Text(stringResource(R.string.projects_create), style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.weight(1f).height(50.dp).semantics { testTag = "project_import" },
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

@Composable
private fun ProjectRow(
    project: Project,
    active: Boolean,
    sessionCount: Int,
    now: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val chat = ChatTheme.chat
    val opened = relativeTimeLabel(project.lastOpenedMs, now)
    val created = relativeTimeLabel(project.createdMs, now)
    val openLabel = stringResource(R.string.projects_open)
    val menuLabel = stringResource(R.string.projects_menu, project.name)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                testTag = "project_row_${project.name}"
                role = Role.Button
            }
            .clickable(onClick = onOpen),
        color = if (active) chat.userBubble else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (active) chat.attention else chat.toolBorder),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    StatusPill(text = stringResource(R.string.projects_current), color = chat.success)
                }
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
            }
            Text(
                text = project.path,
                style = MonoSmall,
                color = chat.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (opened.isEmpty()) {
                            stringResource(R.string.projects_never_opened)
                        } else {
                            stringResource(R.string.projects_last_opened, opened)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                    if (created.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.projects_created, created),
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                        )
                    }
                    if (sessionCount > 0) {
                        Text(
                            text = stringResource(R.string.sessions_count, sessionCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Visual affordance only: the whole row is the button, so this
                // carries no role of its own (a second unnamed "button" inside a
                // button is exactly what makes a screen reader confusing).
                Text(
                    text = openLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .height(44.dp)
                        .padding(horizontal = 10.dp)
                        .semantics { testTag = "project_open_${project.name}" },
                )
            }
        }
    }
}
