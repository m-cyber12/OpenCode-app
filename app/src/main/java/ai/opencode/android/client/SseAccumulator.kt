package ai.opencode.android.client

/**
 * Minimal, dependency-free Server-Sent-Events framing (RFC-style): lines are
 * accumulated until a blank line dispatches the event. `data:` lines are joined
 * with "\n"; `event:`/`id:`/`retry:` are ignored because OpenCode carries the
 * event name inside the JSON payload (`payload.type` / `payload.syncEvent.type`)
 * — the same rule the Phase 3/4 gate drivers verified against the real server.
 *
 * Extracted from [OpenCodeEventStream] so the framing rules are unit-testable
 * without a socket.
 */
class SseAccumulator {

    private val data = StringBuilder()

    /** Feed one line (without its trailing newline). @return a complete payload. */
    fun feed(line: String): String? {
        if (line.isEmpty()) {
            val out = if (data.isEmpty()) null else data.toString()
            data.setLength(0)
            return out
        }
        if (line.startsWith(":")) return null // comment / keepalive
        val field = line.substringBefore(':')
        val value = if (line.contains(':')) line.substringAfter(':').removePrefix(" ") else ""
        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // Ignored on purpose (the type lives in the payload).
            "event", "id", "retry" -> Unit
            else -> Unit
        }
        return null
    }

    /** End of stream: flush a trailing event that was never newline-terminated. */
    fun finish(): String? = if (data.isEmpty()) null else data.toString().also { data.setLength(0) }
}
