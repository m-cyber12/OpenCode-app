package ai.opencode.android.ui.onboarding

import ai.opencode.android.R
import ai.opencode.android.ui.common.SectionCard
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * Phase 10 continuation v4, item 4: the first-run step, and the only screen that
 * asks where the user's files go.
 *
 * What it deliberately does NOT contain, because the earlier flow put all of it
 * here and none of it helped:
 *
 *  * "Copy path" - the folder is printed on this screen and every file manager can
 *    open it; a clipboard copy was a workaround for a location the user could not
 *    reach, which v3 fixed;
 *  * "Export a copy" - same reason: the live project folder IS the copy;
 *  * a choice between "use the default location" and "use a folder I choose", plus
 *    the paragraph explaining the difference between them. There is one folder, it
 *    is shown on screen, and the single action below accepts it;
 *  * anything else: two controls, [onPickFolder] and [onUseFolder]. The screen is a
 *    pure function of the facts it is handed, so the Phase 6 UI gates can render it
 *    with no runtime, no model and no key.
 *
 * The folder that is shown is the workspace the app would use right now (the shared
 * `Documents/OpenCode` default when All files access is held, the app-specific
 * fallback otherwise), and picking a folder replaces it. Confirming is what the
 * caller turns into "create the first project and open its chat" - the screen does
 * not decide that, and it never creates anything itself.
 */
@Composable
fun WorkspaceOnboardingScreen(
    /** The folder projects will live in, as the platform resolved it. */
    folderPath: String,
    /** A file manager can open that folder (drives one honest sentence, nothing else). */
    visibleToFileManagers: Boolean,
    /** Result of the last attempt, or "" - a refusal is never silent. */
    message: String,
    onPickFolder: () -> Unit,
    onUseFolder: () -> Unit,
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
        Spacer(Modifier.height(18.dp))

        SectionCard(title = stringResource(R.string.onboarding_workspace_folder)) {
            Surface(
                color = chat.toolContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = folderPath.ifEmpty { stringResource(R.string.settings_model_none) },
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .semantics { testTag = "onboarding_workspace_path" },
                )
            }
            if (!visibleToFileManagers) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_workspace_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                    modifier = Modifier.semantics { testTag = "onboarding_workspace_hidden" },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
                    .semantics { testTag = "onboarding_workspace_pick" },
            ) {
                Text(stringResource(R.string.onboarding_workspace_pick))
            }
            Button(
                onClick = onUseFolder,
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
                    .semantics { testTag = "onboarding_workspace_use" },
            ) {
                Text(stringResource(R.string.onboarding_workspace_use))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_workspace_later),
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
