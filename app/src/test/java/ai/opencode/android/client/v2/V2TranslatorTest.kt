package ai.opencode.android.client.v2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TranslatorTest {
    private val sid = "ses_1"
    private val mid = "msg_1"

    private fun data(json: String) = JSONObject(json)

    @Test
    fun execution_started_marks_the_session_busy() {
        val f = V2Translator.translate("session.execution.started", data("""{"sessionID":"$sid"}"""))
        assertEquals(listOf("session.status"), f.map { it.type })
        assertEquals("busy", f[0].props.getJSONObject("status").getString("type"))
    }

    @Test
    fun execution_succeeded_goes_idle_and_emits_session_idle() {
        val f = V2Translator.translate("session.execution.succeeded", data("""{"sessionID":"$sid"}"""))
        assertEquals(listOf("session.status", "session.idle"), f.map { it.type })
        assertEquals("idle", f[0].props.getJSONObject("status").getString("type"))
        assertEquals(sid, f[1].props.getString("sessionID"))
    }

    @Test
    fun interrupted_is_treated_like_succeeded_for_the_screen() {
        val f = V2Translator.translate("session.execution.interrupted", data("""{"sessionID":"$sid","reason":"user"}"""))
        assertEquals(listOf("session.status", "session.idle"), f.map { it.type })
    }

    @Test
    fun failure_becomes_session_error_with_message_and_idle() {
        val json = """{"sessionID":"$sid","error":{"type":"provider.free-limit","message":"Free usage limit reached.","status":429}}"""
        val f = V2Translator.translate("session.execution.failed", data(json))
        assertEquals(listOf("session.error", "session.status"), f.map { it.type })
        val err = f[0].props.getJSONObject("error")
        assertEquals("provider.free-limit", err.getString("name"))
        assertEquals("Free usage limit reached.", err.getString("message"))
        assertEquals(429, err.getInt("statusCode"))
        assertEquals("idle", f[1].props.getJSONObject("status").getString("type"))
    }

    @Test
    fun failure_without_details_still_has_a_readable_message() {
        val f = V2Translator.translate("session.step.failed", data("""{"sessionID":"$sid","error":{}}"""))
        assertEquals("The turn failed.", f[0].props.getJSONObject("error").getString("message"))
        assertEquals("UnknownError", f[0].props.getJSONObject("error").getString("name"))
    }

    @Test
    fun step_started_creates_the_assistant_message() {
        val f = V2Translator.translate(
            "session.step.started",
            data("""{"sessionID":"$sid","assistantMessageID":"$mid"}"""),
            created = 1700000000000L,
        )
        assertEquals(listOf("message.updated"), f.map { it.type })
        val info = f[0].props.getJSONObject("info")
        assertEquals(mid, info.getString("id"))
        assertEquals(sid, info.getString("sessionID"))
        assertEquals("assistant", info.getString("role"))
        assertEquals(1700000000000L, info.getJSONObject("time").getLong("created"))
    }

    @Test
    fun text_stream_is_started_then_deltas_then_ended_with_one_stable_part_id() {
        val started = V2Translator.translate("session.text.started", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0}"""))
        val d1 = V2Translator.translate("session.text.delta", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0,"delta":"Hel"}"""))
        val d2 = V2Translator.translate("session.text.delta", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0,"delta":"lo"}"""))
        val ended = V2Translator.translate("session.text.ended", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0,"text":"Hello"}"""))

        val partID = started[0].props.getJSONObject("part").getString("id")
        assertEquals("message.part.updated", started[0].type)
        assertEquals("text", started[0].props.getJSONObject("part").getString("type"))
        assertEquals("", started[0].props.getJSONObject("part").getString("text"))

        assertEquals("message.part.delta", d1[0].type)
        assertEquals(partID, d1[0].props.getString("partID"))
        assertEquals("text", d1[0].props.getString("field"))
        assertEquals("Hel", d1[0].props.getString("delta"))
        assertEquals("lo", d2[0].props.getString("delta"))

        assertEquals(partID, ended[0].props.getJSONObject("part").getString("id"))
        assertEquals("Hello", ended[0].props.getJSONObject("part").getString("text"))
    }

    @Test
    fun reasoning_uses_its_own_part_type_and_id_space() {
        val f = V2Translator.translate("session.reasoning.started", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0}"""))
        val part = f[0].props.getJSONObject("part")
        assertEquals("reasoning", part.getString("type"))
        assertEquals("$mid:reasoning:0", part.getString("id"))
        assertTrue(V2Translator.partID(mid, "text", 0) != part.getString("id"))
    }

    @Test
    fun deltas_without_text_are_dropped() {
        assertTrue(V2Translator.translate("session.text.delta", data("""{"sessionID":"$sid","assistantMessageID":"$mid","ordinal":0,"delta":""}""")).isEmpty())
    }

    @Test
    fun events_without_a_session_are_dropped() {
        assertTrue(V2Translator.translate("session.execution.started", data("""{}""")).isEmpty())
    }

    @Test
    fun envelope_is_unwrapped_and_malformed_input_is_dropped() {
        val f = V2Translator.translateEnvelope(
            """{"id":"evt_1","created":42,"type":"session.execution.started","data":{"sessionID":"$sid"}}""",
        )
        assertEquals(listOf("session.status"), f.map { it.type })
        assertTrue(V2Translator.translateEnvelope("not json").isEmpty())
        assertTrue(V2Translator.translateEnvelope("""{"id":"evt_2","data":{"sessionID":"$sid"}}""").isEmpty())
    }

    @Test
    fun envelope_created_time_reaches_the_assistant_message() {
        val f = V2Translator.translateEnvelope(
            """{"id":"evt_3","created":42,"type":"session.step.started","data":{"sessionID":"$sid","assistantMessageID":"$mid"}}""",
        )
        assertEquals(42L, f[0].props.getJSONObject("info").getJSONObject("time").getLong("created"))
    }

    @Test
    fun permission_ask_becomes_a_v1_permission_frame() {
        val f = V2Translator.translateEnvelope(
            """{"id":"evt_p","created":1,"type":"permission.asked","data":{"id":"per_7","sessionID":"$sid",
            "action":"bash","resources":["rm -rf build"],"save":["bash *"],"source":{"type":"tool","messageID":"$mid","id":"call_3"}}}""",
        )
        assertEquals(listOf("permission.asked"), f.map { it.type })
        val p = f[0].props
        assertEquals("per_7", p.getString("id"))
        assertEquals("bash", p.getString("permission"))
        assertEquals("rm -rf build", p.getJSONArray("patterns").getString(0))
        assertEquals("call_3", p.getJSONObject("tool").getString("callID"))
    }

    @Test
    fun permission_reply_becomes_a_v1_replied_frame() {
        val f = V2Translator.translateEnvelope(
            """{"id":"evt_r","created":2,"type":"permission.replied","data":{"sessionID":"$sid","requestID":"per_7","reply":"once"}}""",
        )
        assertEquals(listOf("permission.replied"), f.map { it.type })
        assertEquals("per_7", f[0].props.getString("requestID"))
        assertEquals("once", f[0].props.getString("reply"))
    }

    @Test
    fun form_created_becomes_a_question_frame_and_reply_or_cancel_settle_it() {
        val created = V2Translator.translateEnvelope(
            """{"id":"evt_f","created":1,"type":"form.created","data":{"form":{"id":"frm_9","sessionID":"$sid","title":"T",
            "fields":[{"type":"string","key":"name","title":"Name"}]}}}""",
        )
        assertEquals(listOf("question.asked"), created.map { it.type })
        assertEquals("frm_9", created[0].props.getString("id"))
        assertEquals("$sid", created[0].props.getString("sessionID"))

        val replied = V2Translator.translateEnvelope(
            """{"id":"evt_g","created":2,"type":"form.replied","data":{"id":"frm_9","sessionID":"$sid","answer":{}}}""",
        )
        assertEquals("question.replied", replied[0].type)
        assertEquals("frm_9", replied[0].props.getString("requestID"))

        val cancelled = V2Translator.translateEnvelope(
            """{"id":"evt_h","created":3,"type":"form.cancelled","data":{"id":"frm_9","sessionID":"$sid"}}""",
        )
        assertEquals("question.rejected", cancelled[0].type)
    }

    @Test
    fun unknown_events_are_ignored_for_now() {
        assertTrue(V2Translator.translate("session.tool.called", data("""{"sessionID":"$sid"}""")).isEmpty())
    }
}
