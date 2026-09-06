package ai.opencode.android.ui.markdown

/**
 * A small, dependency-free markdown parser for chat transcripts.
 *
 * Why it exists and why it is small: the Phase 6 brief wants assistant replies
 * rendered as markdown with syntax-highlighted code, and the standing rule is
 * that the app must not grow third-party dependencies it cannot pin. This file is
 * pure Kotlin - no androidx, no java.net - which is what makes it unit-testable on
 * the JVM (a Phase 6 static check enforces that purity) and keeps the renderer
 * honest about what it does and does not understand.
 *
 * What it handles, all of it common in model output:
 *   fenced code blocks (``` and ~~~, with an info string), ATX headings,
 *   unordered and ordered lists with nesting by indent, blockquotes, thematic
 *   breaks, pipe tables, paragraphs, and inline emphasis (`**bold**`, `*italic*`,
 *   `~~strike~~`), inline code, links and backslash escapes.
 *
 * What it deliberately does NOT do: reference-style links, footnotes, HTML
 * passthrough, setext headings, nested block markup inside tables, and any kind of
 * sanitisation - it never emits HTML, so there is nothing to sanitise. Unhandled
 * markup is shown as the literal text the server sent, which is the correct
 * failure mode for a client that must not silently rewrite an agent's reply.
 *
 * Streaming tolerance matters more than spec completeness here: this parses text
 * that is still arriving (deltas are appended per chunk), so an unterminated fence
 * is a code block so far, and an unterminated `**` or `[label](` stays literal
 * instead of swallowing the rest of the message.
 */
sealed class MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class CodeBlock(val language: String, val source: String) : MdBlock()
    data class ListItem(
        val depth: Int,
        val ordered: Boolean,
        val marker: String,
        val spans: List<MdSpan>,
    ) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock()
    object Rule : MdBlock()
}

sealed class MdSpan {
    /** The visible text of this span (used by tests and by a11y labels). */
    abstract val text: String

    data class Plain(
        override val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdSpan()

    data class Code(override val text: String) : MdSpan()

    data class Link(
        override val text: String,
        val url: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
    ) : MdSpan()
}

object Markdown {

    private val HEADING = Regex("^#{1,6}\\s+.*$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val UL_ITEM = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val OL_ITEM = Regex("^(\\s*)(\\d{1,9})([.)])\\s+(.*)$")
    // The info string after a fence is free text upstream never constrains
    // (`ts {1,20}`, `bash title="x"`), so it is captured whole and normalised.
    private val FENCE = Regex("^(`{3,}|~{3,})[ \\t]*(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** Parse a whole document (or the part of one that has streamed in so far). */
    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val out = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i += 1

                FENCE.matches(trimmed) -> {
                    val m = FENCE.matchEntire(trimmed)!!
                    val fence = m.groupValues[1]
                    val lang = normaliseLanguage(m.groupValues[2])
                    val body = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        val t = lines[j].trim()
                        if (t.startsWith(fence.take(1)) && isClosingFence(t, fence)) {
                            closed = true
                            break
                        }
                        if (body.isNotEmpty()) body.append("\n")
                        body.append(lines[j])
                        j += 1
                    }
                    // An unterminated fence is still a code block: the rest has not
                    // arrived yet, and rendering it as prose would flash the reply.
                    // Trailing newlines are dropped in that case only - they are an
                    // artefact of the line split, not content the server sent.
                    val bodyText = if (closed) body.toString() else body.toString().trimEnd('\n')
                    out.add(MdBlock.CodeBlock(language = lang, source = bodyText))
                    i = if (closed) j + 1 else j
                }

                RULE.matches(trimmed) -> {
                    out.add(MdBlock.Rule)
                    i += 1
                }

                HEADING.matches(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val text = trimmed.substring(level).trim()
                    out.add(MdBlock.Heading(level = level, spans = inline(text)))
                    i += 1
                }

                trimmed.startsWith(">") -> {
                    val buf = ArrayList<String>()
                    var j = i
                    while (j < lines.size) {
                        val t = lines[j].trim()
                        if (!t.startsWith(">")) break
                        buf.add(t.removePrefix(">").trim())
                        j += 1
                    }
                    out.add(MdBlock.Quote(spans = inline(buf.joinToString("\n"))))
                    i = j
                }

                isTableStart(lines, i) -> {
                    val table = parseTable(lines, i)
                    if (table != null) {
                        out.add(table.first)
                        i = table.second
                    } else {
                        out.add(MdBlock.Paragraph(spans = inline(trimmed)))
                        i += 1
                    }
                }

                UL_ITEM.matches(line) || OL_ITEM.matches(line) -> {
                    val items = parseList(lines, i)
                    out.addAll(items.first)
                    i = items.second
                }

                else -> {
                    val buf = ArrayList<String>()
                    var j = i
                    while (j < lines.size) {
                        val raw = lines[j]
                        val t = raw.trim()
                        if (t.isEmpty()) break
                        if (j > i && startsBlock(lines, j)) break
                        buf.add(t)
                        j += 1
                    }
                    // Soft line breaks inside a paragraph are kept: model output uses
                    // single newlines as line breaks far more often than as wrapping.
                    out.add(MdBlock.Paragraph(spans = inline(buf.joinToString("\n"))))
                    i = j
                }
            }
        }
        return out
    }

    /** True when [line] begins a block construct, so a paragraph must stop there. */
    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val t = lines[index].trim()
        return t.isEmpty() ||
            FENCE.matches(t) ||
            RULE.matches(t) ||
            HEADING.matches(t) ||
            t.startsWith(">") ||
            UL_ITEM.matches(lines[index]) ||
            OL_ITEM.matches(lines[index]) ||
            isTableStart(lines, index)
    }

    private fun isClosingFence(trimmed: String, openFence: String): Boolean {
        val ch = openFence[0]
        if (!trimmed.startsWith(ch.toString())) return false
        val run = trimmed.takeWhile { it == ch }
        return run.length >= openFence.length && run.length == trimmed.length
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        val cur = lines[index].trim()
        if (!cur.contains("|")) return false
        val next = index + 1
        if (next >= lines.size) return false
        return TABLE_SEP.matches(lines[next].trim())
    }

    private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
        val header = splitRow(lines[start])
        if (header.isEmpty()) return null
        var j = start + 2
        val rows = ArrayList<List<List<MdSpan>>>()
        while (j < lines.size) {
            val t = lines[j].trim()
            if (t.isEmpty() || !t.contains("|")) break
            if (TABLE_SEP.matches(t)) {
                j += 1
                continue
            }
            rows.add(splitRow(lines[j]).map { inline(it) })
            j += 1
        }
        return MdBlock.Table(header = header.map { inline(it) }, rows = rows) to j
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        // Split on unescaped pipes only; an escaped one is literal text.
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length) {
                cur.append(t[k + 1])
                k += 2
                continue
            }
            if (c == '|') {
                cells.add(cur.toString().trim())
                cur.setLength(0)
                k += 1
                continue
            }
            cur.append(c)
            k += 1
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun parseList(lines: List<String>, start: Int): Pair<List<MdBlock>, Int> {
        val out = ArrayList<MdBlock>()
        var j = start
        while (j < lines.size) {
            val raw = lines[j]
            val ul = UL_ITEM.matchEntire(raw)
            val ol = OL_ITEM.matchEntire(raw)
            if (ul == null && ol == null) {
                // A blank line ends the list; an indented continuation belongs to the
                // previous item (models indent wrapped list text constantly).
                if (raw.trim().isEmpty()) break
                val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                if (indent >= 2 && out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1) as MdBlock.ListItem
                    val merged = last.spans + MdSpan.Plain("\n") + inline(raw.trim())
                    out.add(last.copy(spans = merged))
                    j += 1
                    continue
                }
                break
            }
            val depth: Int
            val ordered: Boolean
            val marker: String
            val text: String
            if (ul != null) {
                depth = indentDepth(ul.groupValues[1])
                ordered = false
                marker = ul.groupValues[2]
                text = ul.groupValues[3]
            } else if (ol != null) {
                depth = indentDepth(ol.groupValues[1])
                ordered = true
                marker = ol.groupValues[2] + ol.groupValues[3]
                text = ol.groupValues[4]
            } else {
                // Unreachable: the loop broke out above when neither matched.
                j += 1
                continue
            }
            out.add(MdBlock.ListItem(depth = depth, ordered = ordered, marker = marker, spans = inline(text)))
            j += 1
        }
        return out to j
    }

    private fun indentDepth(whitespace: String): Int {
        var w = 0
        for (c in whitespace) w += if (c == '\t') 4 else 1
        return w / 2
    }

    /** `Kotlin` -> `kotlin`, `ts {1,20}` -> `ts`, `` -> ``. */
    fun normaliseLanguage(info: String): String {
        val first = info.trim().substringBefore(' ').substringBefore('{').trim()
        return first.lowercase()
    }

    // ---- inline ------------------------------------------------------------

    /** Parse inline markup into spans. Exposed for tests and for one-line callers. */
    fun inline(text: String): List<MdSpan> = spans(text, bold = false, italic = false, strike = false)

    private fun spans(text: String, bold: Boolean, italic: Boolean, strike: Boolean): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val plain = StringBuilder()
        var i = 0
        val n = text.length

        fun flush() {
            if (plain.isNotEmpty()) {
                out.add(MdSpan.Plain(plain.toString(), bold = bold, italic = italic, strike = strike))
                plain.setLength(0)
            }
        }

        while (i < n) {
            val c = text[i]

            // Backslash escape: the next character is literal.
            if (c == '\\' && i + 1 < n && isEscapable(text[i + 1])) {
                plain.append(text[i + 1])
                i += 2
                continue
            }

            // Inline code: a run of backticks closed by a run of the same length.
            if (c == '`') {
                val run = countRun(text, i, '`')
                val close = findClosingRun(text, i + run, '`', run)
                if (close > 0) {
                    flush()
                    var content = text.substring(i + run, close)
                    if (content.startsWith(" ") && content.endsWith(" ") && content.length > 1) {
                        content = content.substring(1, content.length - 1)
                    }
                    out.add(MdSpan.Code(content))
                    i = close + run
                    continue
                }
            }

            // Links: [label](url). An unclosed one stays literal (still streaming).
            if (c == '[') {
                val closeLabel = findUnescaped(text, i + 1, ']')
                if (closeLabel > i && closeLabel + 1 < n && text[closeLabel + 1] == '(') {
                    val closeUrl = findUnescaped(text, closeLabel + 2, ')')
                    if (closeUrl > closeLabel) {
                        val label = text.substring(i + 1, closeLabel)
                        val url = text.substring(closeLabel + 2, closeUrl).trim()
                        flush()
                        // Emphasis inside a label is flattened to its text: a link
                        // span has to carry the whole visible label, and inventing a
                        // nested span tree here would buy nothing on a phone screen.
                        val inner = spans(label, bold = bold, italic = italic, strike = strike)
                        out.add(MdSpan.Link(plainText(inner), url, bold = bold, italic = italic))
                        i = closeUrl + 1
                        continue
                    }
                }
            }

            // Strong / emphasis / strike.
            if (c == '*' || c == '_' || c == '~') {
                val run = countRun(text, i, c)
                val marker = when {
                    c == '~' && run >= 2 -> "~~"
                    run >= 2 -> text.substring(i, i + 2)
                    c != '_' -> text.substring(i, i + 1)
                    canOpenUnderscore(text, i) -> "_"
                    else -> ""
                }
                if (marker.isNotEmpty()) {
                    val close = findMarker(text, i + marker.length, marker)
                    if (close > i) {
                        val inner = text.substring(i + marker.length, close)
                        if (inner.isNotEmpty()) {
                            flush()
                            val nb = bold || marker == "**" || marker == "__"
                            val ni = italic || marker == "*" || marker == "_"
                            val ns = strike || marker == "~~"
                            out.addAll(spans(inner, bold = nb, italic = ni, strike = ns))
                            i = close + marker.length
                            continue
                        }
                    }
                }
            }

            plain.append(c)
            i += 1
        }
        flush()
        return mergeAdjacent(out)
    }

    /** `_` opens emphasis only at a word boundary, so `some_var_name` stays literal. */
    private fun canOpenUnderscore(text: String, i: Int): Boolean {
        val before = if (i == 0) ' ' else text[i - 1]
        val after = if (i + 1 >= text.length) ' ' else text[i + 1]
        return !(before.isLetterOrDigit() || before == '_') && !after.isWhitespace()
    }

    private fun isEscapable(c: Char): Boolean =
        c == '\\' || c == '`' || c == '*' || c == '_' || c == '[' || c == ']' || c == '(' || c == ')' ||
            c == '#' || c == '+' || c == '-' || c == '.' || c == '!' || c == '|' || c == '~' || c == '{' || c == '}'

    private fun countRun(text: String, start: Int, c: Char): Int {
        var k = start
        while (k < text.length && text[k] == c) k += 1
        return k - start
    }

    private fun findClosingRun(text: String, from: Int, c: Char, run: Int): Int {
        var k = from
        while (k < text.length) {
            if (text[k] == '\\') {
                k += 2
                continue
            }
            if (text[k] == c) {
                val r = countRun(text, k, c)
                if (r == run) return k
                k += r
                continue
            }
            k += 1
        }
        return -1
    }

    private fun findUnescaped(text: String, from: Int, c: Char): Int {
        var k = from
        while (k < text.length) {
            if (text[k] == '\\') {
                k += 2
                continue
            }
            if (text[k] == c) return k
            k += 1
        }
        return -1
    }

    /** Find [marker] closing an emphasis run; the content must not start with a space. */
    private fun findMarker(text: String, from: Int, marker: String): Int {
        if (from >= text.length) return -1
        if (text[from].isWhitespace()) return -1
        var k = from
        while (k <= text.length - marker.length) {
            if (text[k] == '\\') {
                k += 2
                continue
            }
            if (text.regionMatches(k, marker, 0, marker.length)) {
                val before = text[k - 1]
                if (!before.isWhitespace()) return k
            }
            k += 1
        }
        return -1
    }

    /** Join neighbouring plain spans that carry the same flags (fewer Text nodes). */
    private fun mergeAdjacent(spans: List<MdSpan>): List<MdSpan> {
        val out = ArrayList<MdSpan>(spans.size)
        for (s in spans) {
            val last = out.lastOrNull()
            if (s is MdSpan.Plain && last is MdSpan.Plain &&
                last.bold == s.bold && last.italic == s.italic && last.strike == s.strike
            ) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else {
                out.add(s)
            }
        }
        return out
    }

    /** Plain text of a block, for accessibility labels and search. */
    fun plainText(spans: List<MdSpan>): String = spans.joinToString("") { it.text }
}
