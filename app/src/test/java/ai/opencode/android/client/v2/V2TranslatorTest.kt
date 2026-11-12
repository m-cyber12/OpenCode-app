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

    /** One call keeps one part id from input start to success; the name comes from the start only. */
    @Test
    fun a_tool_call_is_one_part_from_start_to_success() {
        val mid = "msg_t1"
        val id = "call_1"
        val start = V2Translator.translate(
            "session.tool.input.started",
            data("""{"sessionID":"$sid","assistantMessageID":"$mid","id":"$id","name":"shell"}"""),
            created = 100L,
        )
        val p0 = start[0].props.getJSONObject("part")
        assertEquals(V2Translator.toolPartID(mid, id), p0.getString("id"))
        assertEquals("shell", p0.getString("tool"))
        assertEquals("pending", p0.getJSONObject("state").getString("status"))
        assertEquals(100L, p0.getJSONObject("state").getJSONObject("time").getLong("start"))

        val called = V2Translator.translate(
            "session.tool.called",
            data("""{"sessionID":"$sid","assistantMessageID":"$mid","id":"$id","input":{"command":"ls"},"executed":true}"""),
        )
        val p1 = called[0].props.getJSONObject("part")
        assertEquals(p0.getString("id"), p1.getString("id"))
        assertEquals("running", p1.getJSONObject("state").getString("status"))
        assertEquals("ls", p1.getJSONObject("state").getJSONObject("input").getString("command"))

        val done = V2Translator.translate(
            "session.tool.success",
            data(
                """{"sessionID":"$sid","assistantMessageID":"$mid","id":"$id","executed":true,
                "content":[{"type":"text","text":"a.txt"},{"type":"file","uri":"file:///x","mime":"text/plain","name":"x.txt"}],
                "metadata":{"exit":0}}""",
            ),
        )
        val p2 = done[0].props.getJSONObject("part")
        assertEquals("completed", p2.getJSONObject("state").getString("status"))
        assertEquals("a.txt\nx.txt", p2.getJSONObject("state").getString("output"))
        assertEquals(0, p2.getJSONObject("state").getJSONObject("metadata").getInt("exit"))
        assertTrue("the later frame names no tool; the transcript keeps the name", !p2.has("tool") || p2.getString("tool").isEmpty())
    }

    @Test
    fun a_failed_tool_call_carries_its_error_message() {
        val f = V2Translator.translate(
            "session.tool.failed",
            data(
                """{"sessionID":"$sid","assistantMessageID":"msg_t2","id":"call_2","executed":true,
                "error":{"_tag":"Tool.Error","message":"permission denied"}}""",
            ),
        )
        val state = f[0].props.getJSONObject("part").getJSONObject("state")
        assertEquals("error", state.getString("status"))
        assertEquals("permission denied", state.getString("error"))
    }

    /** History and the stream give the same part id, so reloading does not duplicate a call. */
    @Test
    fun a_reloaded_tool_item_matches_the_streamed_part_id() {
        val msg = JSONObject(
            """{"id":"msg_t3","type":"assistant","time":{"created":5},"agent":"build",
            "content":[{"type":"tool","id":"call_3","name":"shell",
              "state":{"status":"completed","input":{"command":"ls"},
                "content":[{"type":"text","text":"a.txt"}],"metadata":{"exit":0}},
              "time":{"created":6}}]}""",
        )
        val mapped = V2Mapping.message(msg, sid)!!
        val part = mapped.getJSONArray("parts").getJSONObject(0)
        assertEquals("tool", part.getString("type"))
        assertEquals(V2Translator.toolPartID("msg_t3", "call_3"), part.getString("id"))
        assertEquals("shell", part.getString("tool"))
        assertEquals("a.txt", part.getJSONObject("state").getString("output"))
        assertEquals("ls", part.getJSONObject("state").getJSONObject("input").getString("command"))
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

    // ---- shell commands (slice 6) -------------------------------------------

    private val shellStarted = """{"sessionID":"$sid","shell":{"id":"sh_1","status":"running","command":"ls","cwd":"/x","shell":"/bin/sh","file":"/f","metadata":{},"time":{"started":1000}}}"""

    private fun shellEnded(status: String, exit: Int, output: String) =
        """{"sessionID":"$sid","shell":{"id":"sh_1","status":"$status","exit":$exit,"command":"ls","cwd":"/x","shell":"/bin/sh","file":"/f","metadata":{},"time":{"started":1000,"completed":2000}},"output":{"output":"$output","cursor":4,"size":4,"truncated":false}}"""

    private fun partOf(frames: List<V2Translator.Frame>) =
        frames.single { it.type == "message.part.updated" }.props.getJSONObject("part")

    @Test
    fun shell_started_opens_a_running_shell_part_under_its_own_message() {
        val f = V2Translator.translate("session.shell.started", data(shellStarted), 1000L)
        assertEquals(listOf("message.updated", "message.part.updated"), f.map { it.type })
        assertEquals("assistant", f[0].props.getJSONObject("info").getString("role"))
        val part = partOf(f)
        assertEquals("shell", part.getString("tool"))
        assertEquals("running", part.getJSONObject("state").getString("status"))
        assertEquals("ls", part.getJSONObject("state").getJSONObject("input").getString("command"))
    }

    @Test
    fun shell_ended_settles_the_same_part_with_its_output() {
        val started = partOf(V2Translator.translate("session.shell.started", data(shellStarted), 1000L))
        val ended = partOf(V2Translator.translate("session.shell.ended", data(shellEnded("exited", 0, "a\\nb\\n")), 2000L))
        assertEquals(started.getString("id"), ended.getString("id"))
        assertEquals("completed", ended.getJSONObject("state").getString("status"))
        assertEquals("a\nb\n", ended.getJSONObject("state").getString("output"))
    }

    @Test
    fun shell_exit_code_and_timeout_are_errors() {
        val failed = partOf(V2Translator.translate("session.shell.ended", data(shellEnded("exited", 2, "")), 0L))
        assertEquals("error", failed.getJSONObject("state").getString("status"))
        assertEquals("exit 2", failed.getJSONObject("state").getString("error"))
        assertEquals("error", V2Translator.shellState("timeout", null).first)
        assertEquals("timed out", V2Translator.shellState("timeout", null).second)
    }

    /** A reloaded history item lands on the same part id as the live frames, so nothing is shown twice. */
    @Test
    fun shell_history_item_matches_the_live_part() {
        val live = partOf(V2Translator.translate("session.shell.ended", data(shellEnded("exited", 0, "x")), 2000L))
        val item = data("""{"id":"msg_9","type":"shell","shellID":"sh_1","command":"ls","status":"exited","exit":0,"output":{"output":"x","cursor":1,"size":1,"truncated":false},"time":{"created":1000,"completed":2000}}""")
        val mapped = V2Mapping.message(item, sid)!!
        val part = mapped.getJSONArray("parts").getJSONObject(0)
        assertEquals(live.getString("id"), part.getString("id"))
        assertEquals("completed", part.getJSONObject("state").getString("status"))
        assertEquals("x", part.getJSONObject("state").getString("output"))
    }
}
