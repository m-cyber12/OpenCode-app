package ai.opencode.android.client.v2

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Edits an OpenCode config file (`opencode.json` or `opencode.jsonc`) without losing its
 * comments or layout. Only the text of the changed top-level key is replaced, or the key
 * is added after the last member. Everything else stays as it was, byte for byte.
 *
 * Before anything is written, the caller parses the result with [parseObject]. The app
 * never writes a file that its own reader cannot read back.
 */
object JsoncEdit {

    /** The text as the server reads it: comments removed, trailing commas dropped, strings untouched. */
    fun parseObject(text: String): JSONObject = JSONObject(stripComments(text))

    /**
     * [text] with the top-level [key] set to [valueJson], which must already be JSON text.
     * An existing member has its value replaced in place (the last one, if the key repeats).
     * A new key goes after the last member, or into an empty object. Throws if [text] is not
     * a JSON object.
     */
    fun withKey(text: String, key: String, valueJson: String): String {
        val lay = layout(text)
        val existing = lay.members.lastOrNull { it.key == key }
        if (existing != null) {
            return text.substring(0, existing.valueStart) + valueJson + text.substring(existing.valueEnd)
        }
        val member = "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\": " + valueJson
        val last = lay.members.lastOrNull()
        return if (last == null) {
            text.substring(0, lay.openBrace + 1) + "\n  " + member + text.substring(lay.openBrace + 1)
        } else {
            text.substring(0, last.valueEnd) + ",\n  " + member + text.substring(last.valueEnd)
        }
    }

    /** The file text with comments removed and trailing commas dropped. Strings are kept whole. */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                val end = stringEnd(text, i)
                out.append(text, i, end)
                i = end
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                i = skipSpace(text, i)
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i = skipSpace(text, i)
                out.append(' ')
            } else {
                out.append(c)
                i++
            }
        }
        return dropTrailingCommas(out.toString())
    }

    private fun dropTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                val end = stringEnd(text, i)
                out.append(text, i, end)
                i = end
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private class Member(val key: String, val valueStart: Int, val valueEnd: Int)

    private class Layout(val members: List<Member>, val openBrace: Int)

    /** The top-level object's members, with their value spans in the original text (comments kept). */
    private fun layout(text: String): Layout {
        var i = skipSpace(text, 0)
        require(i < text.length && text[i] == '{') { "config is not a JSON object" }
        val open = i
        i++
        val members = ArrayList<Member>()
        while (true) {
            i = skipSpace(text, i)
            require(i < text.length) { "config object is not closed" }
            when (text[i]) {
                '}' -> return Layout(members, open)
                ',' -> i++
                '"' -> {
                    val keyEnd = stringEnd(text, i)
                    val key = JSONTokener(text.substring(i, keyEnd)).nextValue() as? String
                        ?: throw IllegalArgumentException("config key is not a string")
                    i = skipSpace(text, keyEnd)
                    require(i < text.length && text[i] == ':') { "expected ':' after a config key" }
                    i = skipSpace(text, i + 1)
                    val valueStart = i
                    i = skipValue(text, i)
                    members.add(Member(key, valueStart, i))
                }
                else -> throw IllegalArgumentException("unexpected character in config at $i")
            }
        }
    }

    /** Index past the value that starts at [start]: a string, a balanced object or array, or a scalar. */
    private fun skipValue(text: String, start: Int): Int {
        if (text[start] == '"') return stringEnd(text, start)
        if (text[start] == '{' || text[start] == '[') {
            var depth = 0
            var i = start
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> {
                        i = stringEnd(text, i)
                        continue
                    }
                    c == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*') -> {
                        i = skipSpace(text, i)
                        continue
                    }
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                i++
            }
            throw IllegalArgumentException("config value is not closed")
        }
        var i = start
        while (i < text.length && text[i] !in ",}]" && !text[i].isWhitespace() &&
            !(text[i] == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*'))
        ) {
            i++
        }
        return i
    }

    /** Index past the string literal that starts at [start] (which is a quote). */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    /** Skips whitespace and comments from [from]; returns the index of the next token. */
    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("*/", i + 2)
                i = if (close < 0) text.length else close + 2
            } else {
                break
            }
        }
        return i
    }
}
