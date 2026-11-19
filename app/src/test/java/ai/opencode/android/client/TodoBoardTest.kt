package ai.opencode.android.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The working bar's todo list (owner, 2026-11-19: "the list shows up but nothing gets
 * checked off, and no current stage"). The list is the newest `todowrite` call that
 * actually applied: a rejected call and a half-streamed call must not replace it.
 */
class TodoBoardTest {

    private fun list(vararg rows: Pair<String, String>): String =
        JSONObject().put(
            "todos",
            JSONArray(rows.map { JSONObject().put("content", it.first).put("status", it.second) }),
        ).toString()

    private fun part(
        input: String,
        status: String = "completed",
        tool: String = "todowrite",
        metadata: String = "",
    ) = Transcript.Part(
        id = "p-${input.hashCode()}-$status",
        messageID = "m1",
        sessionID = "s1",
        type = "tool",
        text = "",
        tool = tool,
        callID = "c",
        status = status,
        input = input,
        output = "",
        title = "",
        metadata = metadata,
    )

    private fun contents(list: List<TodoItem>) = list.map { it.content }

    @Test
    fun commonSpellingsOfAStatusReadAsTheSameStatus() {
        assertEquals("in_progress", TodoBoard.normalize("in progress"))
        assertEquals("in_progress", TodoBoard.normalize("In-Progress"))
        assertEquals("completed", TodoBoard.normalize("done"))
        assertEquals("completed", TodoBoard.normalize(" Completed "))
        assertEquals("cancelled", TodoBoard.normalize("canceled"))
        assertEquals("pending", TodoBoard.normalize("todo"))
        assertEquals("pending", TodoBoard.normalize("something the model made up"))
    }

    @Test
    fun theListCarriesNormalisedStatuses() {
        val got = TodoParser.parse(list("read" to "done", "write" to "in progress"))
        assertEquals(listOf("completed", "in_progress"), got.map { it.status })
    }

    @Test
    fun aRejectedCallIsSkippedSoTheLastAppliedListStays() {
        // Newest first: the failed call carries statuses the schema refused.
        val newestFirst = sequenceOf(
            part(list("read" to "done", "write" to "doing"), status = "error"),
            part(list("read" to "pending", "write" to "pending")),
        )
        assertEquals(listOf("read", "write"), contents(TodoBoard.latestApplied(newestFirst)))
    }

    @Test
    fun theNewestAppliedListWins() {
        val newestFirst = sequenceOf(
            part(list("read" to "completed", "write" to "in_progress")),
            part(list("read" to "pending", "write" to "pending")),
        )
        val got = TodoBoard.latestApplied(newestFirst)
        assertEquals(listOf("completed", "in_progress"), got.map { it.status })
    }

    @Test
    fun aHalfStreamedCallDoesNotBlankTheList() {
        val newestFirst = sequenceOf(
            part("{\"todos\":[{\"content\":\"wri", status = "pending"),
            part(list("read" to "in_progress")),
        )
        assertEquals(listOf("read"), contents(TodoBoard.latestApplied(newestFirst)))
    }

    @Test
    fun otherToolsAreIgnored() {
        val newestFirst = sequenceOf(
            part(list("ignored" to "pending"), tool = "bash"),
            part(list("read" to "pending")),
        )
        assertEquals(listOf("read"), contents(TodoBoard.latestApplied(newestFirst)))
    }

    @Test
    fun noAppliedListMeansNoList() {
        assertEquals(emptyList<TodoItem>(), TodoBoard.latestApplied(sequenceOf(part("", status = "error"))))
    }

    /** The same path the chat takes: real tool frames, newest part first. */
    @Test
    fun aSecondTodowriteCallSupersedesTheFirstThroughTheTranscript() {
        val t = Transcript()
        fun frame(callID: String, state: String) =
            JSONObject().put("sessionID", "s1").put("messageID", "m1").put(
                "part",
                JSONObject()
                    .put("id", "m1:tool:$callID")
                    .put("messageID", "m1")
                    .put("sessionID", "s1")
                    .put("type", "tool")
                    .put("callID", callID)
                    .put("tool", "todowrite")
                    .put("state", JSONObject(state)),
            )
        t.apply("message.part.updated", frame("c1", """{"status":"completed","input":{"todos":[{"content":"read","status":"pending"},{"content":"write","status":"pending"}]}}"""))
        t.apply("message.part.updated", frame("c2", """{"status":"completed","input":{"todos":[{"content":"read","status":"completed"},{"content":"write","status":"in_progress"}]}}"""))
        val parts = t.snapshot().session("s1")!!.messages.single().parts.asReversed().asSequence()
        val got = TodoBoard.latestApplied(parts)
        assertEquals(listOf("completed", "in_progress"), got.map { it.status })
        assertEquals("write", got.single { it.status == "in_progress" }.content)
    }

    @Test
    fun aFailedSecondCallThroughTheTranscriptKeepsTheFirstList() {
        val t = Transcript()
        fun frame(callID: String, state: String) =
            JSONObject().put("sessionID", "s1").put("messageID", "m1").put(
                "part",
                JSONObject()
                    .put("id", "m1:tool:$callID")
                    .put("messageID", "m1")
                    .put("sessionID", "s1")
                    .put("type", "tool")
                    .put("callID", callID)
                    .put("tool", "todowrite")
                    .put("state", JSONObject(state)),
            )
        t.apply("message.part.updated", frame("c1", """{"status":"completed","input":{"todos":[{"content":"read","status":"in_progress"}]}}"""))
        // A rejected call: the schema refused its status, so its state is an error.
        t.apply("message.part.updated", frame("c2", """{"status":"error","error":"bad status","input":{"todos":[{"content":"read","status":"done-ish"}]}}"""))
        val parts = t.snapshot().session("s1")!!.messages.single().parts.asReversed().asSequence()
        assertEquals(listOf("in_progress"), TodoBoard.latestApplied(parts).map { it.status })
    }
}
