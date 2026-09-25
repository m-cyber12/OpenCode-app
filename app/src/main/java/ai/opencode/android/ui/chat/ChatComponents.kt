package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.ToolKind
import ai.opencode.android.client.ToolKinds
import ai.opencode.android.client.ToolMeta
import ai.opencode.android.client.ToolMetaParser
import ai.opencode.android.client.TodoParser
import ai.opencode.android.client.Transcript
import ai.opencode.android.ui.common.DetailDisclosure
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.common.formatBytes
import ai.opencode.android.ui.common.formatCost
import ai.opencode.android.ui.common.oneLine
import ai.opencode.android.ui.common.relativeTimeLabel
import ai.opencode.android.ui.markdown.CodeBlock
import ai.opencode.android.ui.markdown.MarkdownText
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoBody
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pieces a conversation is made of: turns, tool calls, blocking asks.
 *
 * Everything here is a function of upstream state ([Transcript.Message],
 * [Transcript.Part], [Transcript.Prompt], [Transcript.Question]) plus callbacks.
 * Nothing reads a repository, a runtime or a preference, so any turn - including a
 * failed one, a tool call with a diff, or a permission ask - can be rendered in a
 * Compose UI test from fabricated server state.
 *
 * Two rules the whole file follows:
 *   * the agent's own words and numbers are shown verbatim (tool titles, output,
 *     exit codes, upstream error names and messages);
 *   * nothing important is hidden: reasoning and raw JSON sit behind a disclosure,
 *     a tool call expands on demand, and a FAILED tool call is expanded on arrival
 *     because that is the one thing the user must not miss.
 *
 * Accessibility notes that are easy to get wrong and are therefore written down:
 * every string passed into a `semantics {}` block is hoisted into a val first (a
 * semantics block is not a composable scope), and decorative chevrons clear their
 * semantics so the row they sit in is read once, not twice.
 */

// ---- test tags (the Compose UI gates anchor on these) ----------------------

const val TAG_MESSAGE = "message"
const val TAG_TOOL_CARD = "tool_card"
const val TAG_TOOL_HEADER = "tool_header"
const val TAG_TOOL_OUTPUT = "tool_output"
const val TAG_PERMISSION_ASK = "permission_ask"
const val TAG_PERMISSION_ONCE = "permission_once"
const val TAG_PERMISSION_ALWAYS = "permission_always"
const val TAG_PERMISSION_REJECT = "permission_reject"
const val TAG_QUESTION_ASK = "question_ask"
const val TAG_QUESTION_SUBMIT = "question_submit"
const val TAG_QUESTION_SKIP = "question_skip"
const val TAG_TRANSCRIPT = "transcript"
const val TAG_COMPOSER_INPUT = "composer_input"
const val TAG_COMPOSER_SEND = "composer_send"
const val TAG_COMPOSER_STOP = "composer_stop"
const val TAG_COMPOSER_ATTACH = "composer_attach"
const val TAG_TURN_ERROR = "turn_error"
const val TAG_STREAMING = "streaming_indicator"
const val TAG_MESSAGE_RETRY = "message_retry"
const val TAG_MESSAGE_UNDO = "message_undo"
const val TAG_REASONING = "reasoning"

/** Upstream's own permission reply literals (`PermissionV1.Reply`). */
const val REPLY_ONCE = "once"
const val REPLY_ALWAYS = "always"
const val REPLY_REJECT = "reject"

// ---- a turn ----------------------------------------------------------------

/**
 * One message: a user turn (right, in a bubble) or an assistant turn (full width,
 * so prose and tool cards read as a document rather than as chat bubbles).
 */
@Composable
fun MessageRow(
    message: Transcript.Message,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    streaming: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    showHeader: Boolean = true,
    showFooter: Boolean = true,
    groupTokensIn: Long = -1L,
    groupTokensOut: Long = -1L,
    groupCost: Double = -1.0,
) {
    val chat = ChatTheme.chat
    val tag = "${TAG_MESSAGE}_${message.id}"
    if (message.role == "user") {
        val text = message.parts.filter { it.type == "text" }.joinToString("\n") { it.text }
        val files = message.parts.filter { it.type == "file" }
        Column(
            modifier = modifier.fillMaxWidth().semantics { testTag = tag },
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = chat.userBubble,
                contentColor = chat.onUserBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                shadowElevation = 4.dp,
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (text.isNotBlank()) MarkdownText(source = text, color = chat.onUserBubble)
                    for (f in files) {
                        Spacer(Modifier.height(4.dp))
                        AttachmentChip(
                            filename = f.filename.ifEmpty { f.url.substringAfterLast('/') },
                            mime = f.mime,
                        )
                    }
                }
            }
            val stamp = relativeTimeLabel(message.createdMs, now)
            Text(
                text = if (stamp.isEmpty()) stringResource(R.string.chat_role_you) else stamp,
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
                modifier = Modifier.padding(top = 3.dp, end = 6.dp),
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth().semantics { testTag = tag }) {
        // v8 beauty pass: one quiet line above the turn - a small glyph tile and
        // the model's own name (upstream's provider/model verbatim; the word
        // "Agent" only when the server reported no model). No pill, no second
        // label: the metadata must never compete with the response.
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.project_glyph),
                        style = MonoSmall.copy(fontSize = 9.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = modelLabel(message).ifEmpty { stringResource(R.string.chat_role_agent) },
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (streaming) {
                    StreamingDots()
                } else if (message.completedMs > 0L) {
                    Text(
                        text = relativeTimeLabel(message.completedMs, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        } else if (streaming) {
            // A grouped continuation still has to show that it is streaming.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                StreamingDots()
            }
            Spacer(Modifier.height(6.dp))
        }

        for (part in message.parts) {
            PartRow(part = part, streaming = streaming)
            // Phase 10 polish: 6 -> 10 dp between the pieces of one turn. The
            // brief's "generous whitespace" has to be visible between a sentence
            // and the tool card that follows it, which is where a reader's eye
            // needs the break.
            Spacer(Modifier.height(10.dp))
        }

        val error = message.error
        if (error != null && !error.isBlank) {
            TurnErrorCard(error = error)
            Spacer(Modifier.height(6.dp))
        }

        // One tiny muted line under the whole group - the turn's real totals -
        // instead of a metadata block after every message.
        if (showFooter) {
            val footer = if (groupTokensIn >= 0L) {
                messageFooterText(groupTokensIn, groupTokensOut, groupCost)
            } else {
                messageFooterText(message.tokensInput, message.tokensOutput, message.cost)
            }
            if (footer.isNotEmpty()) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted.copy(alpha = 0.75f),
                )
            }
        }

        if (onRetry != null || onUndo != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.height(44.dp).semantics { testTag = TAG_MESSAGE_RETRY },
                    ) {
                        Text(stringResource(R.string.chat_retry_turn), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (onUndo != null) {
                    TextButton(
                        onClick = onUndo,
                        modifier = Modifier.height(44.dp).semantics { testTag = TAG_MESSAGE_UNDO },
                    ) {
                        Text(stringResource(R.string.chat_undo_turn), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PartRow(part: Transcript.Part, streaming: Boolean) {
    when (part.type) {
        "text" -> if (part.text.isNotBlank()) {
            MarkdownText(source = part.text)
        } else if (streaming) {
            StreamingDots()
        }

        "reasoning" -> ReasoningBlock(part)

        "tool" -> ToolCard(part)

        "file" -> AttachmentChip(
            filename = part.filename.ifEmpty { part.url.substringAfterLast('/') },
            mime = part.mime,
        )

        "retry" -> RetryRow(part)

        "compaction" -> MutedLine(stringResource(R.string.chat_compaction))

        // Upstream bookkeeping with nothing a reader can act on: step markers,
        // snapshot ids, and `patch` (the revert machinery's own record - the diff a
        // user cares about arrives on the tool part, rendered above).
        "step-start", "step-finish", "snapshot", "patch" -> Unit

        else -> UnknownPartRow(part)
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ChatTheme.chat.muted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** Upstream streams reasoning separately; it is available, not headline news. */
@Composable
private fun ReasoningBlock(part: Transcript.Part) {
    if (part.text.isBlank()) return
    val chat = ChatTheme.chat
    var open by rememberSaveable(part.id) { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (open) R.string.chat_hide_reasoning else R.string.chat_show_reasoning,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable { open = !open }
                .semantics {
                    testTag = "${TAG_REASONING}_${part.id}"
                    role = Role.Button
                    contentDescription = toggleLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_reasoning_header),
                style = MaterialTheme.typography.labelMedium,
                color = chat.muted,
                modifier = Modifier.weight(1f),
            )
            Chevron(up = open)
        }
        if (open) {
            Text(
                text = part.text,
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
    }
}

/** A part type this client does not model: say so, and show what the server sent. */
@Composable
private fun UnknownPartRow(part: Transcript.Part) {
    val chat = ChatTheme.chat
    val detail = listOf(
        "type=${part.type}",
        "id=${part.id}",
        part.text.takeIf { it.isNotBlank() },
        part.output.takeIf { it.isNotBlank() },
        part.metadata.takeIf { it.isNotBlank() },
    ).filterNotNull().joinToString("\n")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chat.toolBorder),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(text = part.type, style = MonoSmall, color = chat.muted)
            DetailDisclosure(detail)
        }
    }
}

/** Upstream's own `retry` part: the agent retried a step and says why. */
@Composable
private fun RetryRow(part: Transcript.Part) {
    val chat = ChatTheme.chat
    Surface(color = chat.attentionContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = stringResource(R.string.chat_retry_step),
                style = MaterialTheme.typography.labelMedium,
                color = chat.onAttentionContainer,
            )
            val detail = part.error.ifBlank { part.text }
            if (detail.isNotBlank()) DetailDisclosure(detail)
        }
    }
}

/**
 * A tool call, as the agent reported it.
 *
 * Header: what the tool was (upstream's own name alongside a plain-language
 * category), what it was aimed at (upstream's `title`: the command for bash, the
 * path for read/write/edit) and the agent's own status. Body: the request, the
 * result and - when the agent produced one - the diff with its own
 * additions/deletions counts, exit code, truncation flag and saved-output path.
 */
@Composable
fun ToolCard(part: Transcript.Part, modifier: Modifier = Modifier) {
    val chat = ChatTheme.chat
    val meta = remember(part.metadata) { ToolMetaParser.parse(part.metadata) }
    var expanded by rememberSaveable(part.id) { mutableStateOf(part.status == "error") }
    val statusColor = when (part.status) {
        "completed" -> chat.success
        "error" -> MaterialTheme.colorScheme.error
        "running" -> MaterialTheme.colorScheme.primary
        else -> chat.muted
    }
    val output = meta.output.ifBlank { part.output }
    val statusLabel = toolStatusLabel(part.status)
    val toggleLabel = stringResource(
        if (expanded) R.string.chat_tool_collapse else R.string.chat_tool_expand,
    )
    val headline = toolHeadline(part.tool)
    val title = oneLine(part.title.ifBlank { primaryInputValue(part.input) })

    // The reference direction for a tool card: a mono `[ category · tool ]` kind
    // line, then the command or path as the prominent line - the terminal-flavoured
    // face a developer scans - with the status pill and the chevron on the right.
    // Status stays colour PLUS words (the pill's text), never colour alone.
    // `animateContentSize` keeps expanding a transition rather than a jump - finite
    // on purpose: an infinite animation would keep the Compose test clock busy and
    // hang `waitForIdle()` in the UI gates. The output stays a bordered mono box,
    // so the agent's own words and a command's raw output are never the same
    // surface.
    val todos = if (ToolKinds.of(part.tool) == ToolKind.TODO) {
        remember(part.input, part.metadata) { TodoParser.parse(part.input, part.metadata) }
    } else {
        emptyList()
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = "${TAG_TOOL_CARD}_${part.id}" },
        color = chat.toolContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            1.dp,
            if (part.status == "error") MaterialTheme.colorScheme.error else chat.toolBorder,
        ),
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(
                // Springy, finite (M3-expressive): expanding is a physical motion,
                // and a finite spring keeps the gates' test clock idle.
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics {
                        testTag = "${TAG_TOOL_HEADER}_${part.id}"
                        role = Role.Button
                        contentDescription = toggleLabel
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_tool_kind_frame, headline),
                        style = MonoSmall,
                        color = when (part.status) {
                            "error" -> MaterialTheme.colorScheme.error
                            "running" -> MaterialTheme.colorScheme.primary
                            else -> chat.muted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (title.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = title,
                            style = MonoBody,
                            color = chat.onToolContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                StatusPill(text = statusLabel, color = statusColor)
                Spacer(Modifier.width(6.dp))
                Chevron(up = expanded)
            }

            // A todo tool is a plan: its list is the card's face, checklist-style,
            // always visible - never one tap deeper. Malformed input falls back to
            // the generic face above, with the raw JSON behind the expander.
            if (todos.isNotEmpty()) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    for (item in todos) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            val glyph: String
                            val tint: Color
                            when (item.status) {
                                "completed" -> { glyph = "\u2713"; tint = chat.success }
                                "in_progress" -> { glyph = "\u25CF"; tint = MaterialTheme.colorScheme.primary }
                                "cancelled" -> { glyph = "\u2715"; tint = chat.muted }
                                else -> { glyph = "\u25CB"; tint = chat.muted }
                            }
                            Text(
                                text = glyph,
                                style = MaterialTheme.typography.labelLarge,
                                color = tint,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = item.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item.status == "completed") chat.muted else chat.onToolContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(thickness = 1.dp, color = chat.toolBorder)
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)) {
                    if (part.input.isNotBlank()) {
                        SectionLabel(stringResource(R.string.chat_tool_section_input))
                        ScrollableMono(part.input)
                        Spacer(Modifier.height(10.dp))
                    }

                    if (meta.hasDiff) {
                        SectionLabel(stringResource(R.string.chat_tool_section_diff))
                        if (meta.diffFile.isNotEmpty()) {
                            Text(text = meta.diffFile, style = MonoSmall, color = chat.muted)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.chat_tool_diff_summary, meta.additions, meta.deletions),
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (meta.diff.isNotBlank()) CodeBlock(language = "diff", source = meta.diff)
                        Spacer(Modifier.height(10.dp))
                    }

                    SectionLabel(stringResource(R.string.chat_tool_section_output))
                    if (output.isNotBlank()) {
                        // Terminal treatment: the output is monospace, syntax-tinted
                        // for the languages we can name, and boxed so it cannot be
                        // mistaken for the assistant's own sentence.
                        CodeBlock(
                            language = outputLanguage(part.tool, meta),
                            source = output,
                            modifier = Modifier.semantics { testTag = "${TAG_TOOL_OUTPUT}_${part.id}" },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.chat_tool_no_output),
                            style = MaterialTheme.typography.bodySmall,
                            color = chat.muted,
                        )
                    }

                    val facts = toolFacts(meta)
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (line in facts) {
                            Text(text = line, style = MonoSmall, color = chat.muted)
                        }
                    }

                    if (part.error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_tool_error_detail),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        ScrollableMono(part.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ChatTheme.chat.muted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** Output is height-bounded and scrollable: never truncated, never unbounded. */
@Composable
private fun ScrollableMono(text: String, color: Color = ChatTheme.chat.muted) {
    val background = ChatTheme.code.blockBackground
    Surface(
        color = background,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(text = text, style = MonoSmall, color = color, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AttachmentChip(filename: String, mime: String) {
    val chat = ChatTheme.chat
    val label = if (mime.isNotBlank()) {
        stringResource(R.string.chat_file_part_kind, filename, mime)
    } else {
        stringResource(R.string.chat_file_part, filename)
    }
    Surface(
        color = chat.toolContainer,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(chat.success, CircleShape).clearAndSetSemantics { })
            Spacer(Modifier.width(8.dp))
            Text(
                text = filename,
                style = MaterialTheme.typography.labelMedium,
                color = chat.onToolContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mime.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(text = mime, style = MonoSmall, color = chat.muted)
            }
        }
    }
}

@Composable
private fun StreamingDots() {
    val chat = ChatTheme.chat
    val label = stringResource(R.string.chat_working)
    Row(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .semantics {
                testTag = TAG_STREAMING
                contentDescription = label
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(chat.muted.copy(alpha = if (i == 1) 0.9f else 0.45f), CircleShape)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Decorative chevron: the row it sits in already carries the accessible name.
 *
 * Phase 10 polish: the SAME icon rotates instead of swapping between two icons,
 * so opening a card reads as one continuous motion. It stays `clearAndSetSemantics`
 * - the accessible name belongs to the row, not to the arrow - and the animation
 * is finite, so it cannot hold the Compose test clock open.
 */
@Composable
private fun Chevron(up: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (up) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = ChatTheme.chat.muted,
        modifier = Modifier.size(20.dp).rotate(rotation).clearAndSetSemantics { },
    )
}

/** The error upstream attached to a turn: its name and message, verbatim. */
@Composable
fun TurnErrorCard(error: Transcript.TurnError, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val raw = buildString {
        if (error.name.isNotEmpty()) append(error.name)
        if (error.name.isNotEmpty() && error.message.isNotEmpty()) append(": ")
        append(error.message)
        if (error.statusCode != 0) append(" (status ${error.statusCode})")
        if (error.retryable) append(" [retryable]")
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = TAG_TURN_ERROR },
        color = scheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.chat_turn_failed),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onErrorContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = raw, style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
            DetailDisclosure(raw)
        }
    }
}

// ---- blocking asks ---------------------------------------------------------

/**
 * A permission ask. Upstream blocks the turn until one of its three replies
 * arrives, so while it is pending this is the most important thing on screen:
 * amber rather than red (nothing has failed), above the composer, and it says in
 * words what "always" would cover using upstream's own `always` list.
 */
@Composable
fun PermissionAsk(
    prompt: Transcript.Prompt,
    onReply: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    val bodyText = stringResource(R.string.ask_permission_body, permissionKindLabel(prompt.permission))
    val request = permissionRequestLine(prompt)
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = "${TAG_PERMISSION_ASK}_${prompt.id}" },
        color = chat.attentionContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, chat.attention),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.ask_permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = chat.onAttentionContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = bodyText, style = MaterialTheme.typography.bodyMedium, color = chat.onAttentionContainer)
            if (request.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = Color.Black.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
                    Text(
                        text = request,
                        style = MonoSmall,
                        color = chat.onAttentionContainer,
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    )
                }
            }
            if (prompt.always.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.ask_permission_always_scope, prompt.always.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.onAttentionContainer,
                )
            }
            if (prompt.metadata.isNotBlank()) DetailDisclosure(prompt.metadata)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onReply(prompt.id, REPLY_ONCE) },
                    modifier = Modifier.height(48.dp).weight(1f).semantics { testTag = TAG_PERMISSION_ONCE },
                ) {
                    Text(stringResource(R.string.ask_permission_once), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onReply(prompt.id, REPLY_ALWAYS) },
                    modifier = Modifier.height(48.dp).weight(1f).semantics { testTag = TAG_PERMISSION_ALWAYS },
                    border = BorderStroke(1.dp, chat.attention),
                ) {
                    Text(stringResource(R.string.ask_permission_always), style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = { onReply(prompt.id, REPLY_REJECT) },
                    modifier = Modifier.height(48.dp).weight(1f).semantics { testTag = TAG_PERMISSION_REJECT },
                ) {
                    Text(stringResource(R.string.ask_permission_reject), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * A question ask (`question.asked`). Upstream's question tool blocks a turn the
 * same way a permission does, and its reply shape is `answers: string[][]` - one
 * array of chosen labels per question, in order. A custom answer is simply a label
 * the user typed, which is what upstream expects for `custom` questions.
 */
@Composable
fun QuestionAsk(
    question: Transcript.Question,
    onSubmit: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    val chosen = remember(question.id) { mutableStateOf(List(question.items.size) { emptyList<String>() }) }
    val custom = remember(question.id) { mutableStateOf(List(question.items.size) { "" }) }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = "${TAG_QUESTION_ASK}_${question.id}" },
        color = chat.attentionContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, chat.attention),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.ask_question_title),
                style = MaterialTheme.typography.titleSmall,
                color = chat.onAttentionContainer,
            )
            for ((index, item) in question.items.withIndex()) {
                Spacer(Modifier.height(10.dp))
                if (question.items.size > 1) {
                    Text(
                        text = stringResource(R.string.ask_question_of, index + 1, question.items.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.onAttentionContainer,
                    )
                }
                if (item.header.isNotBlank()) {
                    Text(
                        text = item.header,
                        style = MaterialTheme.typography.labelMedium,
                        color = chat.onAttentionContainer,
                    )
                }
                Text(
                    text = item.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = chat.onAttentionContainer,
                )
                Text(
                    text = stringResource(
                        if (item.multiple) R.string.ask_question_pick_many else R.string.ask_question_pick_one,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.onAttentionContainer,
                )
                Spacer(Modifier.height(6.dp))
                for (option in item.options) {
                    OptionRow(
                        label = option.label,
                        description = option.description,
                        selected = chosen.value.getOrElse(index) { emptyList() }.contains(option.label),
                        multiple = item.multiple,
                        onClick = {
                            val current = chosen.value.getOrElse(index) { emptyList() }
                            val next = when {
                                current.contains(option.label) -> current - option.label
                                item.multiple -> current + option.label
                                else -> listOf(option.label)
                            }
                            chosen.value = chosen.value.setAt(index, next)
                        },
                    )
                }
                if (item.custom) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = custom.value.getOrElse(index) { "" },
                        onValueChange = { text -> custom.value = custom.value.setAt(index, text) },
                        label = { Text(stringResource(R.string.ask_question_custom_label)) },
                        placeholder = { Text(stringResource(R.string.ask_question_custom_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
            val answers = question.items.mapIndexed { index, item ->
                val picked = chosen.value.getOrElse(index) { emptyList() }
                val own = custom.value.getOrElse(index) { "" }.trim()
                when {
                    picked.isNotEmpty() -> picked
                    own.isNotEmpty() -> listOf(own)
                    item.options.isNotEmpty() -> emptyList()
                    else -> emptyList()
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onSubmit(question.id, answers) },
                    enabled = answers.any { it.isNotEmpty() },
                    modifier = Modifier.height(48.dp).weight(1f).semantics { testTag = TAG_QUESTION_SUBMIT },
                ) {
                    Text(stringResource(R.string.ask_question_submit), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onReject(question.id) },
                    modifier = Modifier.height(48.dp).weight(1f).semantics { testTag = TAG_QUESTION_SKIP },
                    border = BorderStroke(1.dp, chat.attention),
                ) {
                    Text(stringResource(R.string.ask_question_skip), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
) {
    val chat = ChatTheme.chat
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = selected, role = if (multiple) Role.Checkbox else Role.RadioButton, onValueChange = { onClick() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = if (selected) chat.attention else Color.Transparent,
            border = BorderStroke(1.dp, chat.attention),
            shape = if (multiple) RoundedCornerShape(4.dp) else CircleShape,
            modifier = Modifier.size(20.dp).clearAndSetSemantics { },
        ) {
            if (selected) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = chat.onAttentionContainer,
                        modifier = Modifier.size(14.dp).clearAndSetSemantics { },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = chat.onAttentionContainer,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.onAttentionContainer,
                )
            }
        }
    }
}

/** Attachment tray above the composer: what will be sent with the next prompt. */
@Composable
fun AttachmentTray(
    attachments: List<OpenCodeApi.Attachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val chat = ChatTheme.chat
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (a in attachments) {
            val label = stringResource(R.string.chat_remove_attachment, a.filename)
            val meta = listOf(a.mime, formatBytes(a.sizeBytes)).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                modifier = Modifier
                    .clickable { onRemove(a.url) }
                    .semantics {
                        testTag = "attachment_${a.filename}"
                        role = Role.Button
                        contentDescription = label
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = a.filename,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (meta.isNotEmpty()) Text(text = meta, style = MonoSmall, color = chat.muted)
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = chat.muted,
                        modifier = Modifier.size(14.dp).clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

// ---- pure presentation helpers --------------------------------------------

private fun <T> List<T>.setAt(index: Int, value: T): List<T> =
    mapIndexed { i, v -> if (i == index) value else v }

@Composable
private fun toolStatusLabel(status: String): String = stringResource(
    when (status) {
        "completed" -> R.string.chat_tool_status_completed
        "error" -> R.string.chat_tool_status_error
        "running" -> R.string.chat_tool_status_running
        else -> R.string.chat_tool_status_pending
    },
)

/** Category first, upstream's own tool name always alongside it. */
@Composable
private fun toolHeadline(tool: String): String {
    val label = when (ToolKinds.of(tool)) {
        ToolKind.SHELL -> stringResource(R.string.chat_tool_kind_shell)
        ToolKind.FILE_READ -> stringResource(R.string.chat_tool_kind_read)
        ToolKind.FILE_WRITE -> stringResource(R.string.chat_tool_kind_write)
        ToolKind.FILE_EDIT -> stringResource(R.string.chat_tool_kind_edit)
        ToolKind.SEARCH -> stringResource(R.string.chat_tool_kind_grep)
        ToolKind.WEB -> stringResource(R.string.chat_tool_kind_websearch)
        ToolKind.TASK -> stringResource(R.string.chat_tool_kind_task)
        ToolKind.TODO -> stringResource(R.string.chat_tool_kind_todo)
        ToolKind.PLAN, ToolKind.MCP, ToolKind.OTHER -> stringResource(R.string.chat_tool_kind_other, tool)
    }
    return "$label \u00b7 $tool"
}

@Composable
private fun permissionKindLabel(permission: String): String = when (permission) {
    "bash" -> stringResource(R.string.ask_permission_kind_bash)
    "edit" -> stringResource(R.string.ask_permission_kind_edit)
    "webfetch" -> stringResource(R.string.ask_permission_kind_web)
    "external_directory" -> stringResource(R.string.ask_permission_kind_external)
    else -> stringResource(R.string.ask_permission_kind_other, permission)
}

/** The most useful single line from the ask: the command, the path or a pattern. */
private fun permissionRequestLine(prompt: Transcript.Prompt): String {
    val command = jsonField(prompt.metadata, "command")
    if (command.isNotBlank()) return command
    val path = jsonField(prompt.metadata, "filePath").ifBlank { jsonField(prompt.metadata, "path") }
    if (path.isNotBlank()) return path
    val title = jsonField(prompt.metadata, "title")
    if (title.isNotBlank()) return title
    return prompt.patterns.joinToString(", ")
}

/**
 * Read one field out of a metadata JSON document on the render path without a JSON
 * parse per frame. Deliberately naive and read-only: it is a display convenience
 * for a field upstream already sent, and the complete JSON is always one tap away
 * in the disclosure next to it.
 */
internal fun jsonField(json: String, key: String): String {
    if (json.isBlank()) return ""
    val needle = "\"$key\""
    val at = json.indexOf(needle)
    if (at < 0) return ""
    var i = at + needle.length
    while (i < json.length && (json[i] == ' ' || json[i] == ':')) i += 1
    if (i >= json.length) return ""
    if (json[i] != '"') {
        var end = i
        while (end < json.length && json[end] != ',' && json[end] != '}' && json[end] != ' ') end += 1
        return json.substring(i, end)
    }
    val sb = StringBuilder()
    i += 1
    while (i < json.length) {
        val c = json[i]
        if (c == '\\' && i + 1 < json.length) {
            when (val nx = json[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                else -> sb.append(nx)
            }
            i += 2
            continue
        }
        if (c == '"') break
        sb.append(c)
        i += 1
    }
    return sb.toString()
}

/** The first command/path-like value in a tool input document. */
private fun primaryInputValue(input: String): String {
    for (key in listOf("command", "filePath", "path", "pattern", "description", "url")) {
        val v = jsonField(input, key)
        if (v.isNotBlank()) return v
    }
    return ""
}

/** Highlight the output in the language the tool actually produced. */
private fun outputLanguage(tool: String, meta: ToolMeta): String = when {
    meta.diff.isNotBlank() -> "diff"
    ToolKinds.of(tool) == ToolKind.SHELL -> "bash"
    tool == "read" || tool == "write" || tool == "edit" -> languageOfPath(meta.diffFile)
    else -> "text"
}

private fun languageOfPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "jsx" -> "javascript"
    "ts", "tsx" -> "typescript"
    "py" -> "python"
    "sh", "bash" -> "bash"
    "json", "jsonc" -> "json"
    "yml", "yaml" -> "yaml"
    "xml", "html" -> "xml"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "hpp", "cc" -> "cpp"
    "sql" -> "sql"
    "md" -> "markdown"
    else -> "text"
}

/** Exit code, truncation, saved-output path and diagnostics count, as facts. */
@Composable
private fun toolFacts(meta: ToolMeta): List<String> {
    val out = ArrayList<String>()
    val exit = meta.exit
    if (exit != null) out.add(stringResource(R.string.chat_tool_exit_code, exit.toString()))
    if (meta.truncated) out.add(stringResource(R.string.chat_tool_truncated))
    if (meta.outputPath.isNotBlank()) out.add(stringResource(R.string.chat_tool_output_file, meta.outputPath))
    if (meta.diagnosticsCount > 0) out.add(stringResource(R.string.chat_tool_diagnostics, meta.diagnosticsCount))
    return out
}

private fun modelLabel(message: Transcript.Message): String {
    val model = message.modelID
    if (model.isBlank()) return ""
    val provider = message.providerID
    return if (provider.isBlank()) model else "$provider/$model"
}

@Composable
private fun messageFooterText(tokensIn: Long, tokensOut: Long, cost: Double): String {
    val bits = ArrayList<String>()
    if (tokensIn > 0L || tokensOut > 0L) {
        bits.add(stringResource(R.string.chat_message_tokens, tokensIn.toInt(), tokensOut.toInt()))
    }
    val costText = formatCost(cost)
    if (costText.isNotEmpty()) bits.add(stringResource(R.string.chat_message_cost, costText))
    return bits.joinToString("  \u00b7  ")
}
