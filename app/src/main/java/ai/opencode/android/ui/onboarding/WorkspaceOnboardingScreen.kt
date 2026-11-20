package ai.opencode.android.ui.onboarding

import ai.opencode.android.R
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/** The two workspace options on the first onboarding screen. `""` means none is chosen yet. */
object WorkspaceChoice {
    const val SELECTED = "selected"
    const val DEFAULT = "default"
}

/**
 * 2026-11-20 (owner, item 6): the first onboarding screen. It replaces the old single-folder
 * "first-time setup" page with two cards, and one of them must be chosen before Next.
 *
 *  * Selected Folder (recommended): a folder on the phone, with two actions - grant storage
 *    permission (on return the folder picker opens at Documents) and choose a folder.
 *  * Default Workspace: the app root. It is explained with its limits, and nothing is asked.
 *
 * The screen is a pure function of the facts it is handed. It never creates anything; Next
 * is the caller's decision. The background setup status is shown here too, so the user sees
 * what is being configured while they choose.
 */
@Composable
fun WorkspaceOnboardingScreen(
    /** The workspace the app would use right now. */
    folderPath: String,
    /** A file manager can open the current workspace folder. */
    visibleToFileManagers: Boolean,
    /** Result of the last attempt, or "" - a refusal is never silent. */
    message: String,
    /** [WorkspaceChoice] value, or "" when nothing is chosen yet. */
    choice: String,
    /** A folder was chosen with the system picker (the Selected Folder option is real). */
    folderChosen: Boolean,
    runtime: RuntimeSummary,
    onChoose: (String) -> Unit,
    onGrantStorage: () -> Unit,
    onPickFolder: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .semantics { testTag = "onboarding_workspace" },
    ) {
        Text(
            text = stringResource(R.string.onboarding_workspace_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_workspace_body),
            style = MaterialTheme.typography.bodyMedium,
            color = chat.muted,
        )
        Spacer(Modifier.height(16.dp))

        WorkspaceOptionCard(
            tag = "onboarding_option_selected",
            selected = choice == WorkspaceChoice.SELECTED,
            title = stringResource(R.string.onboarding_option_selected_title),
            recommended = true,
            body = stringResource(R.string.onboarding_option_selected_body),
            onSelect = { onChoose(WorkspaceChoice.SELECTED) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onGrantStorage,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { testTag = "onboarding_workspace_grant" },
                ) {
                    Text(stringResource(R.string.onboarding_grant_storage), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onPickFolder,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { testTag = "onboarding_workspace_pick" },
                ) {
                    Text(stringResource(R.string.onboarding_workspace_pick), maxLines = 1)
                }
            }
            if (folderChosen) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = folderPath,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { testTag = "onboarding_selected_folder" },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        WorkspaceOptionCard(
            tag = "onboarding_option_default",
            selected = choice == WorkspaceChoice.DEFAULT,
            title = stringResource(R.string.onboarding_option_default_title),
            recommended = false,
            body = stringResource(R.string.onboarding_option_default_body),
            onSelect = { onChoose(WorkspaceChoice.DEFAULT) },
        ) {
            if (!visibleToFileManagers) {
                Text(
                    text = stringResource(R.string.onboarding_workspace_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                    modifier = Modifier.semantics { testTag = "onboarding_workspace_hidden" },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_workspace_folder),
            style = MaterialTheme.typography.labelMedium,
            color = chat.muted,
        )
        Text(
            text = folderPath.ifEmpty { stringResource(R.string.settings_model_none) },
            style = MonoSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { testTag = "onboarding_workspace_path" },
        )

        Spacer(Modifier.height(16.dp))
        OnboardingSetupStatus(runtime = runtime)

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onNext,
            enabled = choice.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { testTag = "onboarding_workspace_use" },
        ) {
            Text(stringResource(R.string.onboarding_next))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (choice.isEmpty()) {
                stringResource(R.string.onboarding_pick_first)
            } else {
                stringResource(R.string.onboarding_workspace_later)
            },
            style = MaterialTheme.typography.bodySmall,
            color = chat.muted,
        )
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = chat.attention,
                modifier = Modifier.semantics { testTag = "onboarding_workspace_message" },
            )
        }
    }
}

/**
 * One option card: a radio row (tapping the title or the radio selects it), an optional
 * Recommended tag, the body text, and the option's own content (its buttons or its notes).
 */
@Composable
private fun WorkspaceOptionCard(
    tag: String,
    selected: Boolean,
    title: String,
    recommended: Boolean,
    body: String,
    onSelect: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chat = ChatTheme.chat
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { testTag = tag },
        color = if (selected) primary.copy(alpha = 0.08f) else chat.toolContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) primary else chat.toolBorder),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (recommended) {
                    StatusPill(text = stringResource(R.string.onboarding_option_recommended), color = chat.success)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
