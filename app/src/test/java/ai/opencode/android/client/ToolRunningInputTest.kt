package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A running tool keeps the command it was called with. Progress frames carry only
 * metadata, and the card read the command back only after the turn ended and the
 * history reloaded (owner, 2026-11-18).
 */
class ToolRunningInputTest {

    private val transcript = Transcript()

    private fun apply(type: String, props: String) {
        transcript.apply(type, JSONObject(props))
    }

    private fun partFrame(state: String) =
        """{"part":{"id":"tp1","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash",
            "callID":"c1","state":$state}}"""

    @Test
    fun aProgressFrameKeepsTheCommandAndTitleOfTheCall() {
        apply("message.part.updated", partFrame("""{"status":"running","input":{"command":"ls -la"},"title":"ls -la"}"""))
        apply("message.part.updated", partFrame("""{"status":"running","metadata":{"exit":null}}"""))
        val part = transcript.snapshot().session("s1")!!.messages.single().parts.single()
        assertEquals("running", part.status)
        assertEquals("the command survives a progress frame", "{\"command\":\"ls -la\"}", part.input)
        assertEquals("ls -la", part.title)
    }

    @Test
    fun aLaterFrameWithItsOwnInputReplacesTheOldOne() {
        apply("message.part.updated", partFrame("""{"status":"pending","input":{"command":"ls"}}"""))
        apply("message.part.updated", partFrame("""{"status":"completed","input":{"command":"ls -la"},"output":"ok"}"""))
        val part = transcript.snapshot().session("s1")!!.messages.single().parts.single()
        assertEquals("{\"command\":\"ls -la\"}", part.input)
        assertEquals("ok", part.output)
    }
}
