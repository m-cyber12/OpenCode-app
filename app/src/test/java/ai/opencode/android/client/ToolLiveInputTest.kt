package ai.opencode.android.client

import ai.opencode.android.client.v2.V2Translator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/**
 * A Write or Edit call streams its arguments before it runs (owner, 2026-11-21: the card looked
 * stuck until the call finished). Each delta extends the part's raw input; the card reads the
 * content and the change from it as it grows.
 */
class ToolLiveInputTest {

    private val transcript = Transcript()
    private val sid = "ses_1"
    private val mid = "msg_1"

    private fun apply(type: String, props: JSONObject) = transcript.apply(type, props)

    private fun started(callID: String, name: String) {
        val frames = V2Translator.translate(
            "session.tool.input.started",
            JSONObject("""{"sessionID":"$sid","assistantMessageID":"$mid","id":"$callID","name":"$name"}"""),
            created = 100L,
        )
        frames.forEach { apply(it.type, it.props) }
    }

    private fun delta(callID: String, text: String) {
        val frames = V2Translator.translate(
            "session.tool.input.delta",
            JSONObject().put("sessionID", sid).put("assistantMessageID", mid).put("id", callID).put("delta", text),
        )
        frames.forEach { apply(it.type, it.props) }
    }

    private fun part(callID: String): Transcript.Part =
        transcript.snapshot().session(sid)!!.messages.single { it.id == mid }
            .parts.single { it.callID == callID }

    @Test
    fun a_write_is_doing_from_its_first_event_with_no_content_yet() {
        started("c1", "write")
        val p = part("c1")
        assertEquals("running", p.status)
        assertEquals("write", p.tool)
        assertEquals("", p.input)
    }

    @Test
    fun deltas_build_the_arguments_as_they_arrive() {
        started("c1", "write")
        delta("c1", "{\"path\":\"notes/a.md\",")
        delta("c1", "\"content\":\"# Title\\n")
        delta("c1", "Body")
        val p = part("c1")
        assertEquals("running", p.status)
        assertEquals("notes/a.md", ToolInputPreview.path(p.input))
        assertEquals("# Title\nBody", ToolInputPreview.writeContent(p.input))
    }

    @Test
    fun the_ended_frame_replaces_the_streamed_text_with_the_full_call() {
        started("c1", "edit")
        delta("c1", "{\"path\":\"x.kt\",\"oldString\":\"a\"")
        val ended = V2Translator.translate(
            "session.tool.input.ended",
            JSONObject("""{"sessionID":"$sid","assistantMessageID":"$mid","id":"c1","text":"{\"path\":\"x.kt\",\"oldString\":\"a\",\"newString\":\"b\"}"}"""),
        )
        ended.forEach { apply(it.type, it.props) }
        val p = part("c1")
        assertEquals("running", p.status)
        assertEquals("b", ToolInputPreview.editNew(p.input))
        assertEquals("a", ToolInputPreview.editOld(p.input))
    }

    @Test
    fun a_delta_for_an_unknown_call_is_ignored() {
        started("c1", "write")
        delta("someone-else", "{\"content\":\"x\"}")
        assertEquals("", part("c1").input)
    }

    @Test
    fun a_delta_before_the_call_has_started_creates_nothing() {
        delta("c1", "{\"content\":\"x\"}")
        assertEquals(null, transcript.snapshot().session(sid))
    }
}
