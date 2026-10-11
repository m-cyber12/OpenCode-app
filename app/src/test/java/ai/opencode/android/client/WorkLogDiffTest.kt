package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.35 (owner): "when the agent creates a new file, only the file's frame
 * appears without the added lines shown". A `write` of a brand-new file
 * carries no server-side diff, so the Diff page framed "+0 -0" around
 * nothing. WorkLog now synthesizes the obvious diff: every line of the
 * write's `content` input is an addition.
 */
class WorkLogDiffTest {

    private fun part(tool: String, input: String, metadata: String = ""): Transcript.Part =
        Transcript.Part(
            id = "prt_$tool",
            messageID = "msg1",
            sessionID = "ses1",
            type = "tool",
            text = "",
            tool = tool,
            callID = "call1",
            status = "completed",
            input = input,
            output = "",
            title = "site/index.html",
            metadata = metadata,
            timeStart = 100L,
            timeEnd = 200L,
        )

    private fun view(vararg parts: Transcript.Part): Transcript.SessionView =
        Transcript.SessionView(
            sessionID = "ses1",
            busy = false,
            messages = listOf(
                Transcript.Message(
                    id = "msg1",
                    sessionID = "ses1",
                    role = "assistant",
                    parts = parts.toList(),
                    completed = true,
                    createdMs = 100L,
                ),
            ),
            pending = emptyList(),
        )

    @Test
    fun newFileWriteSynthesizesAFullAdditionDiff() {
        val content = "<html>\n<body>hi</body>\n</html>"
        val input = JSONObject()
            .put("filePath", "site/index.html")
            .put("content", content)
            .toString()
        val changes = WorkLog.changes(view(part("write", input)))
        assertEquals(1, changes.size)
        val change = changes[0].changes.single()
        assertEquals("site/index.html", change.file)
        assertEquals(3, change.additions)
        assertEquals(0, change.deletions)
        assertTrue(change.patch.startsWith("@@ -0,0 +1,3 @@"))
        assertTrue(change.patch.contains("\n+<html>"))
        assertTrue(change.patch.contains("\n+</html>"))
    }

    @Test
    fun serverDiffStillWinsOverSynthesis() {
        val metadata = JSONObject()
            .put("diff", "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new")
            .put(
                "filediff",
                JSONObject().put("file", "site/index.html")
                    .put("patch", "+new").put("additions", 1).put("deletions", 1),
            )
            .toString()
        val input = JSONObject()
            .put("filePath", "site/index.html")
            .put("content", "irrelevant\nwhen the server has a diff")
            .toString()
        val change = WorkLog.changes(view(part("write", input, metadata)))[0].changes.single()
        assertEquals(1, change.additions)
        assertEquals(1, change.deletions)
        assertFalse(change.patch.startsWith("@@ -0,0"))
        assertTrue(change.patch.contains("+new"))
    }

    @Test
    fun editWithoutDiffIsNotSynthesized() {
        // Synthesis belongs to `write` only: an edit carries old+new strings,
        // not the file content, so there is nothing honest to synthesize.
        val input = JSONObject()
            .put("filePath", "site/index.html")
            .put("oldString", "a")
            .put("newString", "b")
            .toString()
        val change = WorkLog.changes(view(part("edit", input)))[0].changes.single()
        assertEquals(0, change.additions)
        assertEquals("", change.patch)
    }
}
