package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame semantics pinned against shapes captured from the real server in the
 * Phase 3/4 gate logs (legacy bus frames, durable sync frames with a version
 * suffix, and the instance-scoped /event shape).
 */
class EventFrameTest {

    @Test
    fun derivesLegacyType() {
        val payload = JSONObject("""{"type":"message.part.updated","properties":{"sessionID":"ses_1"}}""")
        assertEquals("message.part.updated", EventFrame.deriveType(payload))
        assertEquals("ses_1", EventFrame.properties(payload).getString("sessionID"))
    }

    @Test
    fun stripsDurableVersionSuffix() {
        val payload = JSONObject(
            """{"type":"sync","syncEvent":{"type":"message.part.updated.1","data":{"part":{"id":"p1"}}}}""",
        )
        assertEquals("message.part.updated", EventFrame.deriveType(payload))
        assertEquals("p1", EventFrame.properties(payload).getJSONObject("part").getString("id"))
    }

    @Test
    fun permissionEventsMatchBothSpellings() {
        assertTrue(EventFrame.isPermissionAsked("permission.asked"))
        assertTrue(EventFrame.isPermissionAsked("permission.v2.asked"))
        assertTrue(EventFrame.isPermissionReplied("permission.v2.replied"))
        assertTrue(!EventFrame.isPermissionAsked("permission.updated"))
    }

    @Test
    fun sessionIDFoundInNestedShapes() {
        val direct = JSONObject("""{"sessionID":"ses_a"}""")
        assertEquals("ses_a", EventFrame.sessionIDOf("session.idle", direct))
        val nested = JSONObject("""{"part":{"sessionID":"ses_b"}}""")
        assertEquals("ses_b", EventFrame.sessionIDOf("message.part.updated", nested))
        assertEquals("", EventFrame.sessionIDOf("x", JSONObject()))
    }

    @Test
    fun nullAndUnknownShapesDoNotThrow() {
        assertEquals("", EventFrame.deriveType(null))
        assertEquals(0, EventFrame.properties(null).length())
        assertEquals("", EventFrame.deriveType(JSONObject("""{"nothing":1}""")))
    }
}

/** SSE framing (multi-line data, comments, CRLF, no trailing blank line). */
class SseAccumulatorTest {

    @Test
    fun dispatchesOnBlankLine() {
        val acc = SseAccumulator()
        assertEquals(null, acc.feed("event: message"))
        assertEquals(null, acc.feed("data: {\"a\":1}"))
        assertEquals("""{"a":1}""", acc.feed(""))
    }

    @Test
    fun joinsMultipleDataLines() {
        val acc = SseAccumulator()
        acc.feed("data: one")
        acc.feed("data: two")
        assertEquals("one\ntwo", acc.feed(""))
    }

    @Test
    fun ignoresCommentsAndKeepsFrameBoundaries() {
        val acc = SseAccumulator()
        assertEquals(null, acc.feed(": keepalive"))
        assertEquals(null, acc.feed(""))
        acc.feed("data: {\"x\":2}")
        assertEquals("""{"x":2}""", acc.feed(""))
        // next frame starts clean
        assertEquals(null, acc.feed(""))
    }

    @Test
    fun finishFlushesUnterminatedFrame() {
        val acc = SseAccumulator()
        acc.feed("data: tail")
        assertEquals("tail", acc.finish())
        assertEquals(null, acc.finish())
    }
}

/**
 * The transcript reducer: what the UI shows is exactly what these tests assert,
 * so the rendering contract is pinned without a device.
 */
class TranscriptTest {

    private fun partUpdated(sessionID: String, messageID: String, part: String): JSONObject =
        JSONObject("""{"sessionID":"$sessionID","messageID":"$messageID","part":$part}""")

    @Test
    fun mergesTextPartUpdatesIntoOneMessage() {
        val t = Transcript()
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p1","type":"text","text":"Hel"}"""))
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p1","type":"text","text":"Hello"}"""))
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p2","type":"text","text":" world"}"""))
        val snap = t.snapshot()
        assertEquals(listOf("s1"), snap.sessions.map { it.sessionID })
        val msgs = snap.sessions[0].messages
        assertEquals(1, msgs.size)
        assertEquals(2, msgs[0].parts.size)
        assertEquals("Hello", msgs[0].parts[0].text)
        assertEquals(" world", msgs[0].parts[1].text)
    }

    @Test
    fun toolPartLifecycleKeepsLatestStatus() {
        val t = Transcript()
        t.apply(
            "message.part.updated",
            partUpdated("s1", "m1", """{"id":"pt","type":"tool","tool":"bash","callID":"c1","state":{"status":"running","input":{"command":"ls"}}}"""),
        )
        t.apply(
            "message.part.updated",
            partUpdated("s1", "m1", """{"id":"pt","type":"tool","tool":"bash","callID":"c1","state":{"status":"completed","output":"a\nb"}}"""),
        )
        val part = t.snapshot().sessions[0].messages[0].parts.single()
        assertEquals("completed", part.status)
        assertEquals("a\nb", part.output)
        assertEquals("ls", JSONObject(part.input).getString("command"))
        assertEquals(1, t.snapshot().sessions[0].messages.size)
    }

    @Test
    fun busyFollowsSessionStatusAndIdle() {
        val t = Transcript()
        t.apply("session.status", JSONObject("""{"sessionID":"s1","status":{"type":"busy"}}"""))
        assertTrue(t.snapshot().sessions[0].busy)
        t.apply("session.idle", JSONObject("""{"sessionID":"s1"}"""))
        assertTrue(!t.snapshot().sessions[0].busy)
    }

    @Test
    fun permissionAskThenReplyClears() {
        val t = Transcript()
        t.apply(
            "permission.asked",
            JSONObject("""{"id":"per_1","sessionID":"s1","permission":"bash","patterns":["echo hi"],"metadata":{"command":"echo hi"}}"""),
        )
        val pending = t.snapshot().sessions[0].pending
        assertEquals(1, pending.size)
        assertEquals("bash", pending[0].permission)
        assertEquals(listOf("echo hi"), pending[0].patterns)
        t.apply("permission.replied", JSONObject("""{"requestID":"per_1","reply":"once"}"""))
        assertTrue(t.snapshot().sessions[0].pending.isEmpty())
    }

    @Test
    fun ignoresForeignAndMalformedFrames() {
        val t = Transcript()
        t.apply("session.diff", JSONObject("""{"sessionID":"s1"}"""))
        t.apply("message.part.updated", JSONObject("""{"nope":true}"""))
        t.apply("permission.asked", JSONObject("""{"id":"notper","sessionID":"s1"}"""))
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p1","type":"text","text":"x"}"""))
        val msgs = t.snapshot().sessions[0].messages
        assertEquals(1, msgs.size)
        assertEquals("x", msgs[0].parts.single().text)
        assertTrue(t.snapshot().pendingTotal() == 0)
    }

    @Test
    fun partRemovedDropsThePart() {
        val t = Transcript()
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p1","type":"text","text":"a"}"""))
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p2","type":"text","text":"b"}"""))
        t.apply("message.part.removed", JSONObject("""{"sessionID":"s1","messageID":"m1","partID":"p1"}"""))
        assertEquals(listOf("p2"), t.snapshot().sessions[0].messages[0].parts.map { it.id })
    }

    @Test
    fun sessionDeletedDropsEverything() {
        val t = Transcript()
        t.apply("message.part.updated", partUpdated("s1", "m1", """{"id":"p1","type":"text","text":"a"}"""))
        t.apply("session.deleted", JSONObject("""{"sessionID":"s1"}"""))
        assertTrue(t.snapshot().sessions.isEmpty())
    }
}
