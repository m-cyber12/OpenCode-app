package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v11 (owner, B.108): two field reports.
 *
 *  - "the todo list is not updating, stuck on the first step": the app now takes
 *    upstream's own `todo.updated` frame (the whole list, replaced) and the
 *    `GET /session/:id/todo` repair read, instead of only the last `todowrite` output.
 *  - "diffs failing": apply_patch reports `files[]` (one entry per file) and an
 *    aggregate `diff`, with NO `filediff`. The edits showed as a blank row with +0 -0.
 *
 * Field names come from the pinned upstream (`session/todo.ts`, `tool/apply_patch.ts`).
 */
class TodoAndDiffTest {

    private fun todo(content: String, status: String) =
        JSONObject().put("content", content).put("status", status).put("priority", "high")

    private fun todoFrame(vararg rows: JSONObject): JSONObject =
        JSONObject().put("sessionID", "s1").put("todos", org.json.JSONArray(rows.toList()))

    @Test
    fun todoUpdatedReplacesTheWholeListEachTime() {
        val t = Transcript()
        t.apply(
            "todo.updated",
            todoFrame(todo("read the code", "in_progress"), todo("write the fix", "pending")),
        )
        assertEquals(
            listOf("read the code", "write the fix"),
            t.snapshot().session("s1")?.todos?.map { it.content },
        )

        // Step one is done, step two is now in progress: the list must show THAT, not
        // the first step again (the owner's "stuck on the first step").
        t.apply(
            "todo.updated",
            todoFrame(todo("read the code", "completed"), todo("write the fix", "in_progress")),
        )
        val now = t.snapshot().session("s1")?.todos.orEmpty()
        assertEquals(listOf("completed", "in_progress"), now.map { it.status })
    }

    @Test
    fun todoListAppearsForASessionWithNoMessagesYet() {
        val t = Transcript()
        t.apply("todo.updated", todoFrame(todo("first", "pending")))
        assertEquals(listOf("first"), t.snapshot().session("s1")?.todos?.map { it.content })
    }

    @Test
    fun repairReadSetsTheListAndEmptyClearsIt() {
        val t = Transcript()
        t.setTodos("s1", listOf(TodoItem("a", "pending")))
        assertEquals(listOf("a"), t.snapshot().session("s1")?.todos?.map { it.content })
        t.setTodos("s1", emptyList())
        assertTrue(t.snapshot().session("s1")?.todos.orEmpty().isEmpty())
    }

    @Test
    fun blankTodoRowsAreDropped() {
        val t = Transcript()
        t.apply("todo.updated", todoFrame(todo("   ", "pending"), todo("real", "pending")))
        assertEquals(listOf("real"), t.snapshot().session("s1")?.todos?.map { it.content })
    }

    private val applyPatchMeta = """
        {"diff":"Index: a.txt\n--- a.txt\n+++ a.txt\n@@\n-old\n+new\n+extra\nIndex: b.txt\n",
         "files":[
           {"filePath":"/w/a.txt","relativePath":"a.txt","type":"update",
            "patch":"--- a/a.txt\n+++ b/a.txt\n@@\n-old\n+new\n+extra\n","additions":2,"deletions":1,"movePath":""},
           {"filePath":"/w/b.txt","relativePath":"b.txt","type":"add",
            "patch":"--- /dev/null\n+++ b/b.txt\n@@\n+hello\n","additions":1,"deletions":0,"movePath":""}
         ],
         "diagnostics":{}}
    """.trimIndent()

    @Test
    fun applyPatchFilesAreParsedWithTheirOwnCounts() {
        val meta = ToolMetaParser.parse(applyPatchMeta)
        assertEquals(listOf("a.txt", "b.txt"), meta.files.map { it.path })
        assertEquals(listOf(2, 1), meta.files.map { it.additions })
        assertEquals(listOf(1, 0), meta.files.map { it.deletions })
        // the totals are the sum over the files, not a blank "+0 -0"
        assertEquals(3, meta.additions)
        assertEquals(1, meta.deletions)
        assertTrue(meta.hasDiff)
    }

    @Test
    fun countsFallBackToThePatchWhenTheServerSendsNone() {
        val raw = """{"files":[{"relativePath":"c.txt","patch":"--- a/c.txt\n+++ b/c.txt\n@@\n-x\n+y\n+z\n"}]}"""
        val meta = ToolMetaParser.parse(raw)
        assertEquals(2, meta.files.single().additions)
        assertEquals(1, meta.files.single().deletions)
    }

    @Test
    fun aRemovedLineThatStartsWithDashesIsStillCounted() {
        // An SQL comment removed from a file reads as `--- comment` inside a patch.
        // It is a change, not a file header, so it must count.
        val patch = "--- a/q.sql\n+++ b/q.sql\n@@\n--- old comment\n+new line\n"
        assertEquals(1 to 1, ToolMetaParser.countPatch(patch))
    }

    @Test
    fun workLogSplitsAMultiFileApplyPatchIntoOneChangePerFile() {
        val part = Transcript.Part(
            id = "prt_patch",
            messageID = "msg1",
            sessionID = "s1",
            type = "tool",
            text = "",
            tool = "apply_patch",
            callID = "call1",
            status = "completed",
            input = "{}",
            output = "",
            title = "2 files",
            metadata = applyPatchMeta,
            timeStart = 100L,
            timeEnd = 200L,
        )
        val view = Transcript.SessionView(
            sessionID = "s1",
            busy = false,
            messages = listOf(
                Transcript.Message(
                    id = "msg1",
                    sessionID = "s1",
                    role = "assistant",
                    parts = listOf(part),
                    completed = true,
                    createdMs = 100L,
                ),
            ),
            pending = emptyList(),
        )
        val changes = WorkLog.changes(view).single().changes
        assertEquals(2, changes.size)
        assertEquals(listOf("prt_patch#0", "prt_patch#1"), changes.map { it.partId })
        assertEquals(listOf("a.txt", "b.txt"), changes.map { it.file })
        assertEquals(listOf(2, 1), changes.map { it.additions })
        assertTrue(changes.all { it.patch.isNotBlank() })
        assertNull(changes.firstOrNull { it.file.isEmpty() })
    }
}
