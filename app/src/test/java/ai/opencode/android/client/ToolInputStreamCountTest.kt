package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * JVM tests for the streamed tool-argument path (`tool.input.delta`): the part receives the
 * fragments in order, its counters (shown on the card as "Stream check") count them, and a later
 * progress frame for the same call keeps the counters.
 */
class ToolInputStreamCountTest {

    private val transcript = Transcript()
    private val mid = "m1"
    private val callID = "c1"
    private val partID = "$mid:tool:$callID"

    private fun apply(type: String, json: String) = transcript.apply(type, JSONObject(json))

    private fun runningWrite(status: String = "running") = """
        {"sessionID":"s1","messageID":"$mid","part":{"id":"$partID","messageID":"$mid",
        "sessionID":"s1","type":"tool","tool":"write","callID":"$callID",
        "state":{"status":"$status"}}}
    """.trimIndent()

    private fun delta(fragment: String) = """
        {"sessionID":"s1","messageID":"$mid","callID":"$callID","delta":${JSONObject.quote(fragment)}}
    """.trimIndent()

    private fun part(): Transcript.Part {
        val msg = transcript.snapshot().session("s1")?.messages?.firstOrNull { it.id == mid }
        assertNotNull("message $mid", msg)
        return msg!!.parts.first { it.id == partID }
    }

    @Test
    fun fragmentsAccumulateIntoTheInputAndCountIntoTheCounters() {
        apply("message.part.updated", runningWrite())
        apply("tool.input.delta", delta("{\"path\":\"a.txt\","))
        apply("tool.input.delta", delta("\"content\":\"hello"))
        apply("tool.input.delta", delta(" world\"}"))

        val p = part()
        assertEquals("{\"path\":\"a.txt\",\"content\":\"hello world\"}", p.input)
        assertEquals(3, p.streamFragments)
        assertEquals(p.input.length, p.streamChars)
    }

    @Test
    fun aProgressFrameWithoutInputKeepsTheInputAndTheCounters() {
        apply("message.part.updated", runningWrite())
        apply("tool.input.delta", delta("{\"path\":\"a.txt\"}"))
        apply("message.part.updated", runningWrite(status = "running"))

        val p = part()
        assertEquals("{\"path\":\"a.txt\"}", p.input)
        assertEquals(1, p.streamFragments)
        assertEquals("{\"path\":\"a.txt\"}".length, p.streamChars)
    }
}
