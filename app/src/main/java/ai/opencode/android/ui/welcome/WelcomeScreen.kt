package ai.opencode.android.ui.welcome

import ai.opencode.android.R
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoBody
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The first screen of a fresh install: a short introduction in a few cards, and Next.
 *
 * 2026-11-20 (owner): the setup progress and its messages are gone from onboarding. Setup
 * still runs in the background, invisibly; this screen never waits for it and never reports
 * it. Next sits at the top, like the other onboarding actions, and is always available.
 *
 * The copy must not name a terminal, a shell, a URL, a host, a port or a PC tool: the
 * first-run gate checks the first screens for that vocabulary.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics { testTag = "welcome_screen" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The brand mark: a mono "</>" on a rounded gold tile. Decorative - the title is the name.
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                )
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.project_glyph),
                style = MonoBody,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
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
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { testTag = "welcome_continue" },
        ) {
            Text(stringResource(R.string.onboarding_next), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(20.dp))

        IntroCard(index = 1, title = stringResource(R.string.welcome_card1_title), body = stringResource(R.string.welcome_card1_body))
        Spacer(Modifier.height(10.dp))
        IntroCard(index = 2, title = stringResource(R.string.welcome_card2_title), body = stringResource(R.string.welcome_card2_body))
        Spacer(Modifier.height(10.dp))
        IntroCard(index = 3, title = stringResource(R.string.welcome_card3_title), body = stringResource(R.string.welcome_card3_body))
        Spacer(Modifier.height(10.dp))
        IntroCard(index = 4, title = stringResource(R.string.welcome_card4_title), body = stringResource(R.string.welcome_card4_body))
        Spacer(Modifier.height(24.dp))
    }
}

/** One introduction card: a short title and one or two plain sentences. */
@Composable
private fun IntroCard(index: Int, title: String, body: String) {
    val chat = ChatTheme.chat
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "welcome_card_$index" },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = chat.muted,
            )
        }
    }
}
