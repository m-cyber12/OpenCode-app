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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Choosing and creating the folder the agent works in.
 *
 * Phase 6 keeps this to what the first-run flow needs - list, create, open - and
 * nothing is asked of the user that a phone user should not have to answer: no
 * paths, no ports, no "workspace root". The name they type becomes a directory
 * under the app's own storage ([ProjectStore]), and the server scopes sessions by
 * that directory, so "project" here means exactly what it means to OpenCode.
 *
 * Deeper workspace management (renaming, deleting, importing a tree, git) is
 * Phase 7 by the phase plan, and is deliberately not stubbed here.
 */
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    activeName: String,
    runtimeLine: String,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    Column(modifier = modifier.fillMaxSize().semantics { testTag = "projects_screen" }) {
        AppTopBar(
            title = stringResource(R.string.projects_title),
            subtitle = runtimeLine,
            onBack = onBack,
        )
        CreateProjectCard(onCreate = onCreate, existing = projects.map { it.name })
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
                    now = now,
                    onOpen = { onOpen(project.name) },
                )
            }
        }
    }
}

@Composable
private fun CreateProjectCard(onCreate: (String) -> Unit, existing: List<String>) {
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
private fun ProjectRow(project: Project, active: Boolean, now: Long, onOpen: () -> Unit) {
    val chat = ChatTheme.chat
    val opened = relativeTimeLabel(project.lastOpenedMs, now)
    val created = relativeTimeLabel(project.createdMs, now)
    val openLabel = stringResource(R.string.projects_open)
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
            }
            Spacer(Modifier.height(2.dp))
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
