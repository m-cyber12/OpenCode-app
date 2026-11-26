package ai.opencode.android.visual

import org.json.JSONObject

/**
 * The text protocol the model uses to ask for a visual (spec section 5, `visual.v1`).
 *
 * A marker is `{{visual {json} }}`. It is parsed out of assistant text only:
 *  - a complete marker is removed from the visible text and read as a request;
 *  - an incomplete marker (the reply is still streaming) is hidden until it is complete;
 *  - a malformed marker is hidden and ignored, so it never breaks the reply;
 *  - a marker inside code (fenced or inline) or preceded by a backslash is shown as text
 *    and is never executed;
 *  - at most [MAX_PER_MESSAGE] valid requests are kept, whatever the model asks for.
 *
 * The model may give an intent, a query or a prompt, and alt text. It may not give a URL,
 * a path or a header; an extra key such as `url` is ignored, never used.
 *
 * Nothing consumes [Request] yet, because picture search is not set up. The text is what
 * the chat shows.
 */
object VisualMarkers {
    const val OPEN = "{{visual"
    const val CLOSE = "}}"
    const val PROTOCOL_VERSION = "visual.v1"
    const val MAX_PER_MESSAGE = 2

    private const val MAX_ALT = 200
    private const val MAX_TEXT = 300
    private const val MAX_PAYLOAD = 4000

    /** A valid request. [text] is the search query (kind "search") or the prompt (kind "generate"). */
    data class Request(val kind: String, val text: String, val alt: String)

    /** [visibleText] is what to draw; [requests] are the valid requests; [dropped] counts the ones over the limit. */
    data class Parsed(val visibleText: String, val requests: List<Request>, val dropped: Int)

    /** Fenced code (an unclosed fence runs to the end of the text, so a streaming block stays code) or inline code. */
    private val codeSpan = Regex("```[\\s\\S]*?(?:```|\\z)|`[^`\\n]*`")

    fun visibleText(source: String): String = parse(source).visibleText

    fun parse(source: String): Parsed {
        if (!source.contains(OPEN.substring(0, 2))) return Parsed(source, emptyList(), 0)
        val code = codeSpan.findAll(source).map { it.range }.toList()
        fun isCode(i: Int) = code.any { i in it }

        val out = StringBuilder()
        val requests = ArrayList<Request>()
        var dropped = 0
        var pos = 0
        while (true) {
            val open = source.indexOf(OPEN, pos)
            if (open < 0) break
            if (isCode(open) || (open > 0 && source[open - 1] == '\\')) {
                out.append(source, pos, open + OPEN.length)
                pos = open + OPEN.length
                continue
            }
            out.append(source, pos, open)
            val afterOpen = skipSpaces(source, open + OPEN.length)
            if (afterOpen >= source.length) return Parsed(out.toString(), requests, dropped)
            if (source[afterOpen] != '{') {
                // Not an object: hide up to the closing braces, if they have arrived yet.
                val close = source.indexOf(CLOSE, afterOpen)
                if (close < 0) return Parsed(out.toString(), requests, dropped)
                pos = close + CLOSE.length
                continue
            }
            val objEnd = objectEnd(source, afterOpen)
            if (objEnd < 0) return Parsed(out.toString(), requests, dropped)
            val closeAt = skipSpaces(source, objEnd)
            val tail = source.substring(closeAt)
            if (CLOSE.startsWith(tail) && tail.length < CLOSE.length) {
                // Only part of the closing braces has arrived: still streaming.
                return Parsed(out.toString(), requests, dropped)
            }
            val complete = source.startsWith(CLOSE, closeAt)
            val spanEnd = if (complete) closeAt + CLOSE.length else objEnd
            val payload = source.substring(afterOpen, objEnd)
            if (payload.length <= MAX_PAYLOAD) {
                val request = parseRequest(payload)
                if (request != null && complete) {
                    if (requests.size < MAX_PER_MESSAGE) requests.add(request) else dropped++
                }
            }
            pos = spanEnd
        }
        out.append(source, pos, source.length)
        // A partly typed opening (for example "{{vi" at the end) is hidden while streaming.
        val partial = partialOpenLength(source, pos)
        if (partial > 0) out.setLength(out.length - partial)
        return Parsed(out.toString(), requests, dropped)
    }

    private fun parseRequest(payload: String): Request? {
        val o = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        if (o.optString("version", PROTOCOL_VERSION) != PROTOCOL_VERSION) return null
        val alt = o.optString("alt").trim()
        if (alt.isEmpty() || alt.length > MAX_ALT) return null
        val kind = o.optString("kind")
        val text = when (kind) {
            "search" -> o.optString("query").trim()
            "generate" -> o.optString("prompt").trim()
            else -> return null
        }
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        return Request(kind, text, alt)
    }

    /** Index just after the `}` that closes the object starting at [start] (a `{`), or -1 if it has not arrived. */
    private fun objectEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return -1
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Length of a trailing piece of [OPEN] (at least two characters, so a lone brace stays visible). */
    private fun partialOpenLength(source: String, from: Int): Int {
        val rest = source.length - from
        for (k in minOf(rest, OPEN.length - 1) downTo 2) {
            if (source.endsWith(OPEN.substring(0, k))) return k
        }
        return 0
    }
}
