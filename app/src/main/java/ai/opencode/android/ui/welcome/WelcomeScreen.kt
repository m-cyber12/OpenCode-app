package ai.opencode.android.ui.welcome

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The first thing a fresh install sees.
 *
 * What it must do, and what it must never do:
 *   * it explains the product in one screen and shows the runtime coming up by
 *     itself - the extraction and start happen in the background service the
 *     activity already launched, so this screen only reports;
 *   * it never mentions a terminal, a shell, a URL, a host, a port, Termux, SSH or
 *     a PC. The supervisor's own detail string (which does carry the loopback bind
 *     address) is therefore NOT rendered here; it lives in Settings, next to the
 *     diagnostics, where a user who is troubleshooting has asked to see it. The
 *     Phase 6 first-run gate asserts the absence of that vocabulary on screen.
 */
@Composable
fun WelcomeScreen(
    runtime: RuntimeSummary,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    val ready = runtime.ready
    val stage = welcomeStage(runtime.status)
    val progressLabel = stringResource(R.string.welcome_progress)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = "welcome_screen" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = chat.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(36.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .semantics { testTag = "welcome_stage" },
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageIndicator(ready = ready, label = progressLabel)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = stage.first, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stage.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
                    )
                    if (runtime.restartCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.welcome_restarts, runtime.restartCount),
                            style = MonoSmall,
                            color = chat.attention,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            enabled = ready,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(52.dp)
                .semantics { testTag = "welcome_continue" },
        ) {
            Text(stringResource(R.string.welcome_action_continue), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .height(48.dp)
                .semantics { testTag = "welcome_settings" },
        ) {
            Text(stringResource(R.string.welcome_action_settings), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(24.dp))
        if (runtime.opencodeVersion.isNotEmpty()) {
            Text(
                text = stringResource(R.string.welcome_runtime_line, runtime.opencodeVersion),
                style = MonoSmall,
                color = chat.muted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Progress while the runtime comes up, a tick once it is answering. */
@Composable
private fun StageIndicator(ready: Boolean, label: String) {
    val chat = ChatTheme.chat
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
        if (ready) {
            Surface(color = chat.success, shape = CircleShape, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp).clearAndSetSemantics { },
                    )
                }
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .semantics {
                        testTag = "welcome_progress"
                        contentDescription = label
                    },
                strokeWidth = 3.dp,
                color = chat.success,
            )
        }
    }
}

/** The supervisor's status name onto the copy a first-run user should read. */
@Composable
private fun welcomeStage(status: String): Pair<String, String> {
    val title: String
    val body: String
    when (status) {
        "EXTRACTING" -> {
            title = stringResource(R.string.welcome_stage_extracting_title)
            body = stringResource(R.string.welcome_stage_extracting_body)
        }
        "HEALTHY" -> {
            title = stringResource(R.string.welcome_stage_healthy_title)
            body = stringResource(R.string.welcome_stage_healthy_body)
        }
        "CRASHED_RESTARTING" -> {
            title = stringResource(R.string.welcome_stage_crashed_title)
            body = stringResource(R.string.welcome_stage_crashed_body)
        }
        "STOPPED" -> {
            title = stringResource(R.string.welcome_stage_stopped_title)
            body = stringResource(R.string.welcome_stage_stopped_body)
        }
        "FATAL" -> {
            title = stringResource(R.string.welcome_stage_fatal_title)
            body = stringResource(R.string.welcome_stage_fatal_body)
        }
        "UNSUPPORTED_DEVICE" -> {
            title = stringResource(R.string.welcome_stage_unsupported_title)
            body = stringResource(R.string.welcome_stage_unsupported_body)
        }
        else -> {
            // "" (nothing reported yet) and STARTING both read as "coming up":
            // the honest thing to say before the first health check is that work
            // is in progress, not that something is wrong.
            title = stringResource(R.string.welcome_stage_starting_title)
            body = stringResource(R.string.welcome_stage_starting_body)
        }
    }
    return title to body
}
