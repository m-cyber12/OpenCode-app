package ai.opencode.android.ui.onboarding

import ai.opencode.android.R
import ai.opencode.android.ui.theme.ChatTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import ai.opencode.android.client.CustomProviderConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * The provider screen of the first run: a short intro, Start and "I'll do this later" at the
 * top, then the providers list Settings uses (Zen pinned with its reason).
 *
 * 2026-11-20 (owner): the setup status is not shown here, and the two exits are at the top.
 * Both open Projects. The providers list and its key form arrive as slots, so this screen
 * stays a layout and the Settings wiring stays in one place.
 */
@Composable
fun ProviderOnboardingScreen(
    /** A key is saved, so Start may open Projects. Without one, only "I'll do this later" works. */
    canStart: Boolean,
    /** The providers list. The lambda gets the add-form state and the toggle for it. */
    catalog: @Composable (addOpen: Boolean, onToggleAdd: () -> Unit) -> Unit,
    /** The saved custom providers, each with Edit (which opens the form filled in) and Remove. */
    customList: @Composable (onEdit: (CustomProviderConfig.Saved) -> Unit) -> Unit = { _ -> },
    /** The custom-provider form, shown under the list while it is open. [editing] is the provider being edited, if any. */
    addForm: @Composable (editing: CustomProviderConfig.Saved?) -> Unit,
    onStart: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomProviderConfig.Saved?>(null) }
    val chat = ChatTheme.chat
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .semantics { testTag = "onboarding_provider" },
    ) {
        Text(
            text = stringResource(R.string.onboarding_provider_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_provider_body),
            style = MaterialTheme.typography.bodyMedium,
            color = chat.muted,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { testTag = "onboarding_provider_start" },
        ) {
            Text(stringResource(R.string.onboarding_start), textAlign = TextAlign.Center)
        }
        if (!canStart) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.onboarding_start_needs_key),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
                modifier = Modifier.semantics { testTag = "onboarding_start_needs_key" },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLater,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { testTag = "onboarding_provider_later" },
        ) {
            Text(stringResource(R.string.onboarding_later), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        catalog(addOpen) {
            addOpen = !addOpen
            editing = null
        }
        customList { saved ->
            editing = saved
            addOpen = true
        }
        if (addOpen) {
            Spacer(Modifier.height(8.dp))
            addForm(editing)
        }
    }
}
