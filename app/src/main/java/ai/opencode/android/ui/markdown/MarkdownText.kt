package ai.opencode.android.ui.markdown

import ai.opencode.android.R
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.CodePalette
import androidx.compose.foundation.BorderStroke
import ai.opencode.android.ui.theme.MonoBody
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Compose half of the markdown renderer.
 *
 * All textual decisions are made by [Markdown] and [CodeHighlight] (pure Kotlin,
 * covered by JVM unit tests). This file only turns their output into composables,
 * so what a screen shows is exactly what the parser produced - the renderer never
 * re-interprets, trims or "cleans up" an agent's reply.
 *
 * Two deliberate choices:
 *   * prose is selectable ([SelectionContainer]), because copying an answer out of
 *     a chat is the single most common thing a user does with it. A paragraph that
 *     contains a link trades selection for [ClickableText] instead, since Compose
 *     1.6 cannot do both in one node.
 *   * code blocks scroll horizontally rather than wrapping: a wrapped shell command
 *     or diff is worse than useless, it is misleading.
 */

/** Render markdown source. Cheap enough to call for every streamed frame. */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    MarkdownBlocks(blocks = Markdown.parse(source), modifier = modifier, style = style, color = color)
}

/** Render pre-parsed blocks (for callers that keep the parse across recompositions). */
@Composable
fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val chat = ChatTheme.chat
    Column(modifier = modifier.fillMaxWidth()) {
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) Spacer(Modifier.height(7.dp))
            when (block) {
                is MdBlock.Paragraph -> ProseText(block.spans, style = style, color = color)

                is MdBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Spacer(Modifier.height(4.dp))
                    ProseText(block.spans, style = headingStyle, color = color)
                }

                is MdBlock.CodeBlock -> CodeBlock(language = block.language, source = block.source)

                is MdBlock.ListItem -> ListRow(block, style = style, color = color)

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Surface(
                        modifier = Modifier.width(3.dp).fillMaxHeight(),
                        color = chat.muted,
                        shape = RoundedCornerShape(2.dp),
                    ) {}
                    Spacer(Modifier.width(10.dp))
                    ProseText(
                        block.spans,
                        style = style,
                        color = chat.muted,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }

                is MdBlock.Table -> TableBlock(block, color = color)

                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** A paragraph / heading / list item body: spans with emphasis, inline code, links. */
@Composable
fun ProseText(
    spans: List<MdSpan>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val chat = ChatTheme.chat
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(spans, color, linkColor, codeBackground, chat.toolBorder) {
        annotateSpans(
            spans = spans,
            color = color,
            linkColor = linkColor,
            codeColor = chat.success,
            codeBackground = codeBackground,
        )
    }
    val hasLink = spans.any { it is MdSpan.Link }
    if (hasLink) {
        val handler = LocalUriHandler.current
        ClickableText(
            text = annotated,
            style = style,
            modifier = modifier.fillMaxWidth(),
            onClick = { offset ->
                annotated.getStringAnnotations(TAG_URL, offset, offset)
                    .firstOrNull()
                    ?.let { ann -> openLinkSafely(handler, ann.item) }
            },
        )
    } else {
        SelectionContainer(modifier = modifier) {
            Text(text = annotated, style = style, color = color, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A fenced code block: language header, copy action, highlighted selectable body. */
@Composable
fun CodeBlock(
    language: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val palette = ChatTheme.code
    val clipboard = LocalClipboardManager.current
    val langLabel = if (language.isNotEmpty()) language else stringResource(R.string.code_language_plain)
    val annotated = remember(source, language, palette) { annotateCode(language, source, palette) }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = TAG_CODE_BLOCK },
        color = palette.blockBackground,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = langLabel,
                    style = MonoSmall,
                    color = palette.comment,
                    modifier = Modifier.padding(start = 2.dp).weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(source)) }) {
                    Text(
                        text = stringResource(R.string.action_copy_code),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(
                    text = annotated,
                    style = MonoBody,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .semantics { testTag = TAG_CODE_BODY },
                )
            }
        }
    }
}

@Composable
private fun ListRow(item: MdBlock.ListItem, style: TextStyle, color: Color) {
    val indent: Dp = (item.depth * 18).dp
    Row(Modifier.fillMaxWidth().padding(start = indent)) {
        Text(
            text = if (item.ordered) item.marker else BULLET,
            style = style,
            color = color,
            modifier = Modifier.width(if (item.ordered) 26.dp else 16.dp),
        )
        ProseText(item.spans, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, color: Color) {
    val bodyStyle = MaterialTheme.typography.bodySmall
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
            TableRow(table.header, bodyStyle, color, bold = true)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            for (row in table.rows) {
                TableRow(row, bodyStyle, color, bold = false)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<MdSpan>>, style: TextStyle, color: Color, bold: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (cell in cells) {
            val text = Markdown.plainText(cell)
            Text(
                text = text,
                style = style,
                color = color,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// ---- pure helpers (no composition) -----------------------------------------

private const val TAG_URL = "URL"
private const val BULLET = "\u2022"

/** Test tags the Compose UI gates anchor on. */
const val TAG_CODE_BLOCK = "code_block"
const val TAG_CODE_BODY = "code_body"

private fun annotateSpans(
    spans: List<MdSpan>,
    color: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        when (s) {
            is MdSpan.Plain -> withStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                ),
            ) { append(s.text) }

            is MdSpan.Code -> {
                withStyle(
                    SpanStyle(
                        color = codeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        background = codeBackground,
                    ),
                ) { append(s.text) }
            }

            is MdSpan.Link -> {
                pushStringAnnotation(TAG_URL, s.url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = if (s.bold) FontWeight.SemiBold else null,
                        fontStyle = if (s.italic) FontStyle.Italic else null,
                    ),
                ) { append(s.text) }
                pop()
            }
        }
    }
}

/**
 * Turn [CodeHighlight]'s token roles into colours. This mapping is the ONLY place
 * the app decides what a token looks like, which is why the palette is theme-aware
 * rather than baked into the tokeniser.
 */
internal fun annotateCode(language: String, source: String, palette: CodePalette): AnnotatedString =
    buildAnnotatedString {
        for (span in CodeHighlight.tokenize(language, source)) {
            withStyle(SpanStyle(color = colorOf(span.token, palette))) { append(span.text) }
        }
    }

private fun colorOf(token: CodeToken, palette: CodePalette): Color = when (token) {
    CodeToken.KEYWORD -> palette.keyword
    CodeToken.STRING -> palette.string
    CodeToken.COMMENT -> palette.comment
    CodeToken.NUMBER -> palette.number
    CodeToken.FUNCTION -> palette.function
    CodeToken.TYPE -> palette.type
    CodeToken.PUNCTUATION -> palette.punctuation
    CodeToken.PLAIN -> palette.plain
}

/**
 * Open a link from agent output, but only schemes that mean something on a phone
 * and cannot be used to hand the app's own loopback address to another process.
 * A malformed or unsupported URI is ignored rather than crashing the transcript.
 */
private fun openLinkSafely(handler: UriHandler, url: String) {
    val lower = url.trim().lowercase()
    val allowed = lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("mailto:")
    if (!allowed) return
    runCatching { handler.openUri(url.trim()) }
}
