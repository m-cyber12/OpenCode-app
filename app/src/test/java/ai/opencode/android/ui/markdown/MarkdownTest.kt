package ai.opencode.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the markdown parser.
 *
 * These run on the host, with no emulator and no runtime, because the parser is
 * pure Kotlin - which is exactly why the Phase 6 static check forbids androidx
 * imports in `ui/markdown/Markdown.kt`. The fixtures are the shapes model output
 * actually arrives in, including the half-finished ones a streaming transcript
 * produces (an unterminated fence, an unclosed `**`, a link whose URL has not
 * arrived yet).
 */
class MarkdownTest {

    private fun blocks(source: String) = Markdown.parse(source)

    private fun firstParagraph(source: String): MdBlock.Paragraph =
        blocks(source).filterIsInstance<MdBlock.Paragraph>().first()

    private fun text(spans: List<MdSpan>) = Markdown.plainText(spans)

    @Test
    fun paragraphIsOneBlockAndKeepsSoftLineBreaks() {
        val parsed = blocks("hello world\nsecond line")
        assertEquals(1, parsed.size)
        val p = parsed[0] as MdBlock.Paragraph
        assertEquals("hello world\nsecond line", text(p.spans))
    }

    @Test
    fun blankLineSeparatesParagraphs() {
        assertEquals(2, blocks("one\n\ntwo").size)
    }

    @Test
    fun atxHeadingsCarryTheirLevel() {
        val parsed = blocks("# Title\n### Third")
        assertEquals(2, parsed.size)
        assertEquals(1, (parsed[0] as MdBlock.Heading).level)
        assertEquals(3, (parsed[1] as MdBlock.Heading).level)
        assertEquals("Title", text((parsed[0] as MdBlock.Heading).spans))
    }

    @Test
    fun fencedCodeBlockKeepsLanguageAndByteExactSource() {
        val source = "Here you go:\n```kotlin\nval x = 1\nprintln(x)\n```\nDone."
        val parsed = blocks(source)
        val code = parsed.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.source)
        assertEquals(3, parsed.size)
    }

    @Test
    fun tildeFenceAndInfoStringAttributes() {
        val code = blocks("~~~ts {1,20}\nlet a = 1\n~~~").filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("ts", code.language)
        assertEquals("let a = 1", code.source)
    }

    @Test
    fun unterminatedFenceIsStillCodeBecauseTheRestHasNotArrived() {
        val parsed = blocks("```bash\nls -la\n")
        val code = parsed.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("bash", code.language)
        assertEquals("ls -la", code.source)
    }

    @Test
    fun listsCarryDepthMarkerAndOrdering() {
        val parsed = blocks("- one\n- two\n  - nested\n1. first\n2. second")
        val items = parsed.filterIsInstance<MdBlock.ListItem>()
        assertEquals(5, items.size)
        assertEquals(0, items[0].depth)
        assertEquals(false, items[0].ordered)
        assertEquals(1, items[2].depth)
        assertEquals(true, items[3].ordered)
        assertEquals("1.", items[3].marker)
        assertEquals("first", text(items[3].spans))
    }

    @Test
    fun indentedContinuationJoinsThePreviousItem() {
        val items = blocks("- a long item\n  that wraps").filterIsInstance<MdBlock.ListItem>()
        assertEquals(1, items.size)
        assertTrue(text(items[0].spans).contains("that wraps"))
    }

    @Test
    fun blockquoteAndRule() {
        val parsed = blocks("> quoted line\n\n---\n")
        assertTrue(parsed.any { it is MdBlock.Quote })
        assertTrue(parsed.any { it === MdBlock.Rule })
        assertEquals("quoted line", text((parsed[0] as MdBlock.Quote).spans))
    }

    @Test
    fun pipeTableSplitsCells() {
        val parsed = blocks("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val table = parsed.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.header.map { text(it) })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { text(it) })
    }

    @Test
    fun boldItalicStrikeAndInlineCode() {
        val spans = firstParagraph("a **bold** b *it* c ~~gone~~ d `code` e").spans
        assertTrue(spans.any { it is MdSpan.Plain && it.text == "bold" && it.bold })
        assertTrue(spans.any { it is MdSpan.Plain && it.text == "it" && it.italic })
        assertTrue(spans.any { it is MdSpan.Plain && it.text == "gone" && it.strike })
        assertTrue(spans.any { it is MdSpan.Code && it.text == "code" })
    }

    @Test
    fun strongUsesDoubleMarkersAndNestsEmphasis() {
        val spans = firstParagraph("**bold and *italic* inside**").spans
        assertTrue(spans.any { it is MdSpan.Plain && it.text.contains("bold and") && it.bold })
        assertTrue(spans.any { it is MdSpan.Plain && it.text == "italic" && it.bold && it.italic })
    }

    @Test
    fun linksCarryLabelAndUrl() {
        val link = firstParagraph("see [the docs](https://example.com/x) now").spans
            .filterIsInstance<MdSpan.Link>().single()
        assertEquals("the docs", link.text)
        assertEquals("https://example.com/x", link.url)
    }

    @Test
    fun underscoreInsideAWordIsNotEmphasis() {
        val spans = firstParagraph("call some_var_name here").spans
        assertEquals("call some_var_name here", text(spans))
        assertTrue(spans.none { it is MdSpan.Plain && it.italic })
    }

    @Test
    fun backslashEscapesAreLiteral() {
        assertEquals("a * b", text(firstParagraph("a \\* b").spans))
    }

    @Test
    fun unterminatedEmphasisStaysLiteralWhileStreaming() {
        assertEquals("still **stream", text(firstParagraph("still **stream").spans))
    }

    @Test
    fun unterminatedLinkStaysLiteralWhileStreaming() {
        assertEquals("see [the docs](htt", text(firstParagraph("see [the docs](htt").spans))
    }

    @Test
    fun inlineSpanTextAlwaysConcatenatesBackToTheVisibleText() {
        val source = "**bold** and `code` and [link](https://example.com) and plain"
        val spans = firstParagraph(source).spans
        assertEquals("bold and code and link and plain", text(spans))
    }

    @Test
    fun emptyInputProducesNoBlocks() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("\n\n").isEmpty())
    }

    @Test
    fun crlfIsNormalised() {
        val code = blocks("```sh\r\nls\r\n```").filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("ls", code.source)
    }

    @Test
    fun languageNormalisationIsLowercaseAndAliasFree() {
        assertEquals("kotlin", Markdown.normaliseLanguage("Kotlin"))
        assertEquals("bash", Markdown.normaliseLanguage("bash"))
        assertEquals("", Markdown.normaliseLanguage(""))
    }
}
