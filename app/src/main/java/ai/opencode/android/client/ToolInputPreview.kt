package ai.opencode.android.client

/**
 * What a file tool is writing or changing, read out of its arguments WHILE they are still being
 * generated (owner, 2026-11-21: Write and Edit cards must show the content and the diff live).
 *
 * The arguments arrive as text: upstream streams `session.tool.input.delta` fragments, so the
 * JSON is usually unfinished. These readers take the string values that have been written so
 * far, and they never guess a value the model has not produced yet. Upstream's own argument
 * names (v2.2.5): write `path`, `content`; edit `path`, `oldString`, `newString`, `replaceAll`;
 * patch `patchText`.
 *
 * Once a call completes, the card shows the server's own diff from the tool's metadata instead,
 * and this preview is no longer used.
 */
object ToolInputPreview {

    /**
     * The decoded value of a TOP-LEVEL string field of a (possibly unfinished) JSON object, or
     * null when the field has not started yet or its value is not a string. A key with the same
     * name inside another value is not a match: only keys at depth 1 count.
     */
    fun stringField(json: String, key: String): String? {
        val n = json.length
        var i = 0
        var depth = 0
        while (i < n) {
            when (json[i]) {
                '"' -> {
                    val token = readString(json, i)
                    i = token.end
                    if (depth == 1 && token.decoded == key) {
                        var j = skipSpaces(json, i)
                        if (j < n && json[j] == ':') {
                            j = skipSpaces(json, j + 1)
                            if (j >= n) return null
                            if (json[j] != '"') return null
                            return readString(json, j).decoded
                        }
                    }
                }
                '{', '[' -> {
                    depth++
                    i++
                }
                '}', ']' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    /** The content of a write so far. */
    fun writeContent(json: String): String = stringField(json, "content").orEmpty()

    /** The file a write, edit or patch is changing, when its path has been generated. */
    fun path(json: String): String = stringField(json, "path").orEmpty()

    /** The text an edit replaces, so far. */
    fun editOld(json: String): String = stringField(json, "oldString").orEmpty()

    /** The text an edit puts in place of the old text, so far. */
    fun editNew(json: String): String = stringField(json, "newString").orEmpty()

    /** The patch text of an apply_patch call, so far. */
    fun patchText(json: String): String = stringField(json, "patchText").orEmpty()

    /**
     * A live diff of an edit: every line of the old text as removed, then every line of the new
     * text as added. It is not a line-level diff - that would need the whole text - but it shows
     * exactly what is leaving and what is arriving while the call is still being written.
     */
    fun editDiff(oldText: String, newText: String): String {
        if (oldText.isEmpty() && newText.isEmpty()) return ""
        val out = StringBuilder()
        if (oldText.isNotEmpty()) for (line in oldText.split('\n')) out.append('-').append(line).append('\n')
        if (newText.isNotEmpty()) for (line in newText.split('\n')) out.append('+').append(line).append('\n')
        return out.toString().trimEnd('\n')
    }

    /** Lines a text has so far (a trailing partial line counts). */
    fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    private class Token(val decoded: String, val end: Int)

    /**
     * Reads the JSON string that starts at [start] (a quote). An unfinished string - the stream
     * has not reached its closing quote yet - decodes what has arrived, and its [Token.end] is the
     * end of the text.
     */
    private fun readString(json: String, start: Int): Token {
        val n = json.length
        var i = start + 1
        val raw = StringBuilder()
        while (i < n) {
            val c = json[i]
            if (c == '"') return Token(decode(raw.toString()), i + 1)
            if (c == '\\') {
                if (i + 1 >= n) break // an escape the stream has not finished
                raw.append(c).append(json[i + 1])
                i += 2
                continue
            }
            raw.append(c)
            i++
        }
        return Token(decode(raw.toString()), n)
    }

    /** Decodes JSON escapes. A `\u` that is not complete yet is left out, not guessed. */
    private fun decode(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= raw.length) break
            when (val e = raw[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '"', '\\', '/' -> out.append(e)
                'u' -> {
                    if (i + 6 > raw.length) return out.toString()
                    val code = raw.substring(i + 2, i + 6).toIntOrNull(16) ?: return out.toString()
                    out.append(code.toChar())
                    i += 6
                    continue
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }

    private fun skipSpaces(json: String, from: Int): Int {
        var i = from
        while (i < json.length && json[i].isWhitespace()) i++
        return i
    }
}
