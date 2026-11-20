package ai.opencode.android.ui.onboarding

import ai.opencode.android.R
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.theme.ChatTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp

/**
 * 2026-11-20 (owner, item 6): the background backend setup, shown while onboarding runs.
 * It names what is being configured and the stage it has reached. The stage comes from the
 * supervisor's own status, so nothing here is a made-up progress bar. Host and port are never
 * shown on this surface.
 */
@Composable
fun OnboardingSetupStatus(
    runtime: RuntimeSummary,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = "onboarding_setup_status" },
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.onboarding_setup_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_setup_what),
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(stageLabel(runtime.status)),
                style = MaterialTheme.typography.bodySmall,
                color = when (runtime.status) {
                    "HEALTHY" -> chat.success
                    "CRASHED_RESTARTING", "FATAL", "UNSUPPORTED_DEVICE" -> MaterialTheme.colorScheme.error
                    else -> chat.attention
                },
                modifier = Modifier.semantics { testTag = "onboarding_setup_stage" },
            )
        }
    }
}

private fun stageLabel(status: String): Int = when (status) {
    "EXTRACTING" -> R.string.onboarding_stage_unpacking
    "STARTING" -> R.string.onboarding_stage_starting
    "HEALTHY" -> R.string.onboarding_stage_ready
    "STOPPED" -> R.string.onboarding_stage_stopped
    "CRASHED_RESTARTING", "FATAL", "UNSUPPORTED_DEVICE" -> R.string.onboarding_stage_failed
    else -> R.string.onboarding_stage_waiting
}

/**
 * 2026-11-20 (owner, item 6): the second onboarding screen. A short intro, the same providers
 * list Settings uses (Zen pinned at the top with its reason), the setup status, and two
 * exits: "I'll do this later" and "Start". Both open Projects.
 *
 * The providers list and its key form are passed in as slots, so this screen stays a layout
 * and the Settings wiring stays in one place.
 */
@Composable
fun ProviderOnboardingScreen(
    runtime: RuntimeSummary,
    /** The providers list. The lambda gets the add-form state and the toggle for it. */
    catalog: @Composable (addOpen: Boolean, onToggleAdd: () -> Unit) -> Unit,
    /** The custom-provider form, shown under the list while it is open. */
    addForm: @Composable () -> Unit,
    onStart: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
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
        Spacer(Modifier.height(14.dp))
        OnboardingSetupStatus(runtime = runtime)
        Spacer(Modifier.height(14.dp))
        catalog(addOpen) { addOpen = !addOpen }
        if (addOpen) {
            Spacer(Modifier.height(8.dp))
            addForm()
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { testTag = "onboarding_provider_start" },
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLater,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { testTag = "onboarding_provider_later" },
        ) {
            Text(stringResource(R.string.onboarding_later))
        }
    }
}
