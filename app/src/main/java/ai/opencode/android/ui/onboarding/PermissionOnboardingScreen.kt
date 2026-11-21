package ai.opencode.android.ui.onboarding

import ai.opencode.android.R
import ai.opencode.android.ui.settings.PERMISSION_KEYS
import ai.opencode.android.ui.settings.PermissionToolRow
import ai.opencode.android.ui.settings.permissionLabelRes
import ai.opencode.android.ui.theme.ChatTheme
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * The permission step of the first run, between the workspace and the provider.
 *
 * 2026-11-21 (owner): the five permission rows are on this page itself, not in a collapsed
 * section. A tap shows at once and Next does not wait for it: the write to the server runs in
 * the background (the repository keeps the choice until the server has it). Next sits at the
 * top, like the other onboarding actions, and is always available - keeping "Ask" is a choice too.
 *
 * The rows are the same ones Settings shows, so the two places cannot drift apart.
 */
@Composable
fun PermissionOnboardingScreen(
    /** The stored policy, with the taps that are still being written already applied. */
    policy: Map<String, String>,
    onPolicy: (String, String) -> Unit,
    onBashPolicy: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .semantics { testTag = "onboarding_permission" },
    ) {
        Text(
            text = stringResource(R.string.settings_section_permissions),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_permissions_body),
            style = MaterialTheme.typography.bodyMedium,
            color = chat.muted,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { testTag = "onboarding_permission_next" },
        ) {
            Text(stringResource(R.string.onboarding_next))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboarding_permission_later),
            style = MaterialTheme.typography.bodySmall,
            color = chat.muted,
        )
        Spacer(Modifier.height(16.dp))

        for (key in PERMISSION_KEYS) {
            val res = permissionLabelRes(key) ?: continue
            PermissionToolRow(
                label = stringResource(res),
                current = policy[key] ?: "ask",
                onPolicy = onPolicy,
                onBashPolicy = onBashPolicy,
                key = key,
            )
        }
    }
}
