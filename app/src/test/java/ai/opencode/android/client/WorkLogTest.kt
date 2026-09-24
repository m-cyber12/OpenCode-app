package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7: the read models behind the Changes and Terminal surfaces.
 *
 * These are pure projections of a [Transcript.SessionView], so the whole
 * behaviour is testable here: which parts count as a file change, which as a
 * console entry, how turns group and order, and that garbage input never throws.
 */
class WorkLogTest {

    private fun part(
        id: String,
        tool: String,
        input: String = "",
        metadata: String = "",
        status: String = "completed",
        type: String = "tool",
        title: String = "",
        output: String = "",
        start: Long = 0L,
        end: Long = 0L,
    ) = Transcript.Part(
        id = id, messageID = "m", sessionID = "s", type = type, text = "",
        tool = tool, callID = "c_$id", status = status, input = input,
        output = output, title = title, metadata = metadata,
        timeStart = start, timeEnd = end,
    )

    private fun turn(id: String, parts: List<Transcript.Part>, role: String = "assistant", created: Long = 1000L) =
        Transcript.Message(
            id = id, sessionID = "s", role = role, parts = parts,
            completed = true, createdMs = created,
        )

    private fun view(vararg messages: Transcript.Message) = Transcript.SessionView(
        sessionID = "s", busy = false, messages = messages.toList(), pending = emptyList(),
    )

    private val editMeta =
        """{"filediff":{"file":"src/App.kt","patch":"--- a\n+++ b\n+new line","additions":3,"deletions":1}}"""

    // ---- changes ----

    @Test
    fun anEditBecomesAStageWithTheFileAndTheCounts() {
        val v = view(turn("m1", listOf(part("p1", "edit", metadata = editMeta))))
        val stages = WorkLog.changes(v)
        assertEquals(1, stages.size)
        val change = stages[0].changes.single()
        assertEquals("src/App.kt", change.file)
        assertEquals(3, change.additions)
        assertEquals(1, change.deletions)
        assertTrue(change.patch.contains("+new line"))
        assertEquals(3, stages[0].additions)
        assertEquals(1, stages[0].deletions)
    }

    @Test
    fun aWriteWithoutADiffStillCounts_theFileComesFromTheInput() {
        val v = view(turn("m1", listOf(part("p1", "write", input = """{"filePath":"notes.md"}"""))))
        val stages = WorkLog.changes(v)
        assertEquals("notes.md", stages[0].changes.single().file)
    }

    @Test
    fun readsSearchesAndShellRunsAreNotChanges() {
        val v = view(
            turn(
                "m1",
                listOf(
                    part("p1", "read", input = """{"filePath":"a.txt"}"""),
                    part("p2", "grep", input = """{"pattern":"x"}"""),
                    part("p3", "bash", input = """{"command":"ls"}"""),
                ),
            ),
        )
        assertTrue(WorkLog.changes(v).isEmpty())
    }

    @Test
    fun turnsWithoutFileChangesAreOmittedAndNewestStageLeads() {
        val v = view(
            turn("m1", listOf(part("p1", "edit", metadata = editMeta)), created = 1000L),
            turn("m2", listOf(part("p2", "bash", input = """{"command":"ls"}"""))),
            turn("m3", listOf(part("p3", "edit", metadata = editMeta)), created = 3000L),
        )
        val stages = WorkLog.changes(v)
        assertEquals(listOf("m3", "m1"), stages.map { it.messageID })
        // The stage number still counts forward in time: m1 was the first stage.
        assertEquals(listOf(3, 1), stages.map { it.turnIndex })
    }

    @Test
    fun userTurnsAndNonToolPartsNeverAppear() {
        val v = view(
            turn("m1", listOf(part("p1", "edit", metadata = editMeta)), role = "user"),
            turn("m2", listOf(part("p2", "", type = "text"))),
        )
        assertTrue(WorkLog.changes(v).isEmpty())
    }

    // ---- terminal ----

    @Test
    fun aShellCallBecomesAConsoleEntryWithCommandOutputAndExit() {
        val meta = """{"output":"total 4\n", "exit":0}"""
        val v = view(
            turn(
                "m1",
                listOf(part("p1", "bash", input = """{"command":"ls -la","description":"List files"}""", metadata = meta, start = 100L, end = 350L)),
            ),
        )
        val entry = WorkLog.commands(v).single()
        assertEquals("ls -la", entry.command)
        assertEquals("List files", entry.description)
        assertEquals("total 4\n", entry.output)
        assertEquals(0, entry.exit)
        assertEquals(250L, entry.durationMs)
    }

    @Test
    fun theConsoleReadsTopDown_oldestFirst_acrossTurns() {
        val v = view(
            turn("m1", listOf(part("p1", "bash", input = """{"command":"first"}"""))),
            turn("m2", listOf(part("p2", "shell", input = """{"command":"second"}"""))),
        )
        assertEquals(listOf("first", "second"), WorkLog.commands(v).map { it.command })
    }

    @Test
    fun runningAndFailedCommandsAreShownNotHidden() {
        val v = view(
            turn(
                "m1",
                listOf(
                    part("p1", "bash", input = """{"command":"sleep 60"}""", status = "running"),
                    part("p2", "bash", input = """{"command":"false"}""", status = "error"),
                ),
            ),
        )
        val entries = WorkLog.commands(v)
        assertEquals(listOf("running", "error"), entries.map { it.status })
        assertNull(entries[0].exit)
    }

    @Test
    fun editsAndReadsAreNotConsoleEntries() {
        val v = view(turn("m1", listOf(part("p1", "edit", metadata = editMeta))))
        assertTrue(WorkLog.commands(v).isEmpty())
    }

    // ---- robustness ----

    @Test
    fun aNullSessionAndGarbageJsonProduceEmptyPagesNotCrashes() {
        assertTrue(WorkLog.changes(null).isEmpty())
        assertTrue(WorkLog.commands(null).isEmpty())
        val v = view(
            turn(
                "m1",
                listOf(
                    part("p1", "bash", input = "{not json", metadata = "also not json"),
                    part("p2", "edit", input = "", metadata = "{}"),
                ),
            ),
        )
        // The broken shell call still shows up (title fallback), the metadata-less
        // edit still counts as a change with zero counts.
        assertEquals(1, WorkLog.commands(v).size)
        assertEquals(1, WorkLog.changes(v).size)
        assertEquals(0, WorkLog.changes(v)[0].additions)
    }
}
