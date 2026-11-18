package ai.opencode.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall

/**
 * The one error surface. Every failure the chat can show goes through here, so the
 * user meets the same layout each time: a bordered card, a plain headline, one line
 * on what to do, and the agent's own words behind "Show details" with a copy button.
 *
 * Tone follows who can act on it: [Tone.FAILURE] (the agent or its server, red),
 * [Tone.ATTENTION] (the model, a tool, a permission, or a stop you asked for, amber),
 * and [Tone.NEUTRAL] (the agent is simply stopped).
 */
@Composable
fun ErrorCard(
    kind: AgentAvailability,
    modifier: Modifier = Modifier,
    rawDetail: String = "",
    extra: String = "",
    tag: String = "availability_banner",
    onDismiss: (() -> Unit)? = null,
) {
    val tone = toneOf(kind)
    val chat = ChatTheme.chat
    val container = when (tone) {
        Tone.FAILURE -> MaterialTheme.colorScheme.errorContainer
        Tone.ATTENTION -> chat.attentionContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (tone) {
        Tone.FAILURE -> MaterialTheme.colorScheme.onErrorContainer
        Tone.ATTENTION -> chat.onAttentionContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accent = when (tone) {
        Tone.FAILURE -> MaterialTheme.colorScheme.error
        Tone.ATTENTION -> chat.attention
        Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
    val headline = availabilityHeadline(kind)
    val body = availabilityBody(kind)

    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = tag },
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(14.dp),
        // A strong border in the tone's colour: the card reads as a deliberate notice,
        // not a stray line of text, and the colour is never the only signal (the
        // headline and the badge say what happened).
        border = BorderStroke(1.5.dp, accent),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badgeGlyph(tone),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = onContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.92f),
                    )
                    if (extra.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = extra, style = MonoSmall, color = onContainer)
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = onContainer,
                        )
                    }
                }
            }
            if (rawDetail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                RawDetail(text = rawDetail, tint = onContainer)
            }
        }
    }
}

private enum class Tone { FAILURE, ATTENTION, NEUTRAL }

private fun toneOf(kind: AgentAvailability): Tone = when (kind) {
    AgentAvailability.RUNTIME_UNAVAILABLE,
    AgentAvailability.SERVER_UNREACHABLE,
    AgentAvailability.SERVER_AUTH,
    AgentAvailability.REQUEST_REJECTED,
    AgentAvailability.UNKNOWN,
    -> Tone.FAILURE
    AgentAvailability.PROVIDER_UNREACHABLE,
    AgentAvailability.PROVIDER_AUTH,
    AgentAvailability.PROVIDER_OTHER,
    AgentAvailability.PROVIDER_NOT_SET_UP,
    AgentAvailability.CONTENT_FILTERED,
    AgentAvailability.CONTEXT_TOO_LONG,
    AgentAvailability.OUTPUT_TOO_LONG,
    AgentAvailability.TOOL_FAILED,
    AgentAvailability.PERMISSION_REJECTED,
    AgentAvailability.COMPACTION_FAILED,
    AgentAvailability.ABORTED,
    -> Tone.ATTENTION
    else -> Tone.NEUTRAL
}

private fun badgeGlyph(tone: Tone): String = when (tone) {
    Tone.FAILURE -> "!"
    Tone.ATTENTION -> "!"
    Tone.NEUTRAL -> "i"
}

/**
 * The agent's own words, verbatim, behind a disclosure. Monospace at a readable size,
 * selectable, with a copy button, so a report of the error can be pasted as it was.
 */
@Composable
private fun RawDetail(text: String, tint: Color) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var copied by remember(text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(if (open) R.string.action_hide_details else R.string.action_details),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
            )
        }
        if (open) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        text = stringResource(R.string.error_raw_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MonoSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(if (copied) R.string.error_copied else R.string.error_copy),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
