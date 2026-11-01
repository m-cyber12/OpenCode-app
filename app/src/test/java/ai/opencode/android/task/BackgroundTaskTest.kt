package ai.opencode.android.task

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackgroundTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A session that is busy for [busyPolls] reads after the prompt, then idle. */
    private class FakePort(
        var busyPolls: Int = 2,
        val neverIdle: Boolean = false,
        val failStatus: Boolean = false,
        val failCreate: Boolean = false,
        val onPrompt: () -> Unit = {},
    ) : BackgroundTask.Port {
        var aborted = false
        var prompts = 0
        override fun createSession(title: String): String {
            if (failCreate) throw IllegalStateException("server down")
            return "ses_1"
        }

        override fun prompt(sessionID: String, text: String) {
            prompts++
            onPrompt()
        }

        override fun busy(sessionID: String): Boolean {
            if (failStatus) throw IllegalStateException("401")
            if (neverIdle) return true
            if (busyPolls > 0) {
                busyPolls--
                return true
            }
            return false
        }

        override fun abort(sessionID: String) {
            aborted = true
        }

        override fun lastReply(sessionID: String): String = "Wrote the report."
    }

    private fun spec(minutes: Int = 1, done: String = "reports/debt.md") =
        BackgroundTask.Spec("debt-1", "Find the technical debt.", minutes, done)

    @Test
    fun parsesASpecWithDefaultsAndRejectsUnsafeOnes() {
        val ok = BackgroundTask.parse(
            "debt-1",
            """{"prompt":"Find debt","done_when":{"file":"reports/debt.md"}}""",
        ) as BackgroundTask.Parsed.Ok
        assertEquals(BackgroundTask.DEFAULT_MINUTES, ok.spec.minutes)
        assertEquals("reports/debt.md", ok.spec.doneFile)

        fun reason(text: String, name: String = "t1"): String {
            val r = BackgroundTask.parse(name, text)
            assertTrue("expected invalid, got $r", r is BackgroundTask.Parsed.Invalid)
            return (r as BackgroundTask.Parsed.Invalid).reason
        }
        assertTrue(reason("""{"prompt":"x","done_when":{"file":"a"}}""", "bad name").contains("file name"))
        assertTrue(reason("nope").contains("not valid JSON"))
        assertTrue(reason("""{"done_when":{"file":"a"}}""").contains("prompt must be"))
        assertTrue(reason("""{"prompt":"x"}""").contains("done_when must name a file"))
        assertTrue(reason("""{"prompt":"x","minutes":0,"done_when":{"file":"a"}}""").contains("minutes must be"))
        assertTrue(reason("""{"prompt":"x","minutes":61,"done_when":{"file":"a"}}""").contains("minutes must be"))
        assertTrue(reason("""{"prompt":"x","done_when":{"file":"../secret"}}""").contains("relative path"))
        assertTrue(reason("""{"prompt":"x","done_when":{"file":"/etc/hosts"}}""").contains("relative path"))
        assertTrue(reason("""{"prompt":"x","done_when":{"file":"C:/x"}}""").contains("relative path"))
        assertTrue(reason("""{"prompt":"x","done_when":{"file":""}}""").contains("relative path"))
    }

    @Test
    fun decideLetsTheCompletionFileWinAndAnErrorBeatEverything() {
        assertEquals(BackgroundTask.Outcome.DONE, BackgroundTask.decide(doneExists = true, timedOut = false, error = null))
        assertEquals(BackgroundTask.Outcome.DONE, BackgroundTask.decide(doneExists = true, timedOut = true, error = null))
        assertEquals(BackgroundTask.Outcome.TIMEOUT, BackgroundTask.decide(doneExists = false, timedOut = true, error = null))
        assertEquals(BackgroundTask.Outcome.INCOMPLETE, BackgroundTask.decide(doneExists = false, timedOut = false, error = null))
        assertEquals(BackgroundTask.Outcome.FAILED, BackgroundTask.decide(doneExists = true, timedOut = false, error = "x"))
    }

    @Test
    fun runsToDoneWhenTheFileAppearsAndTheTurnEnds() = runBlocking {
        var exists = false
        // The agent writes the file during its turn; the turn then goes idle.
        val port = FakePort(busyPolls = 2, onPrompt = { exists = true })
        val f = BackgroundTask.run(
            spec(),
            port,
            doneExists = { exists },
            limitMs = 5_000,
            pollMs = 1,
            graceMs = 50,
        )
        assertEquals(BackgroundTask.Outcome.DONE, f.outcome)
        assertEquals(1, port.prompts)
        assertFalse(port.aborted)
        assertEquals("ses_1", f.sessionID)
        assertEquals("Wrote the report.", f.lastReply)
    }

    @Test
    fun abortsAtTheTimeLimitAndReportsTimeout() = runBlocking {
        val port = FakePort(neverIdle = true)
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, limitMs = 30, pollMs = 2, graceMs = 5)
        assertEquals(BackgroundTask.Outcome.TIMEOUT, f.outcome)
        assertTrue(port.aborted)
    }

    @Test
    fun endsIncompleteWhenTheTurnEndsWithoutTheFile() = runBlocking {
        val port = FakePort(busyPolls = 1)
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, limitMs = 5_000, pollMs = 1, graceMs = 50)
        assertEquals(BackgroundTask.Outcome.INCOMPLETE, f.outcome)
        assertFalse(port.aborted)
    }

    @Test
    fun aSessionThatIsNeverBusyEndsAfterTheGrace() = runBlocking {
        val port = FakePort(busyPolls = 0)
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, limitMs = 5_000, pollMs = 1, graceMs = 5)
        assertEquals(BackgroundTask.Outcome.INCOMPLETE, f.outcome)
    }

    @Test
    fun failsWhenTheSessionCannotBeCreated() = runBlocking {
        val f = BackgroundTask.run(spec(), FakePort(failCreate = true), doneExists = { false }, pollMs = 1)
        assertEquals(BackgroundTask.Outcome.FAILED, f.outcome)
        assertTrue(f.error!!.contains("could not create a session"))
    }

    @Test
    fun failsWhenTheStatusKeepsFailing() = runBlocking {
        val port = FakePort(failStatus = true)
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, limitMs = 5_000, pollMs = 1, graceMs = 50)
        assertEquals(BackgroundTask.Outcome.FAILED, f.outcome)
        assertTrue(f.error!!.contains("status unreadable"))
        assertTrue(port.aborted)
    }

    @Test
    fun reportStartsWithTheVerdictAndNamesTheCompletionFile() {
        val f = BackgroundTask.Finished(BackgroundTask.Outcome.INCOMPLETE, "ses_9", 1200, false, "line one\nline two", null)
        val text = BackgroundTask.report(spec(), f)
        assertTrue(text.startsWith("TASK debt-1 INCOMPLETE ms=1200 session=ses_9\n"))
        assertTrue(text.contains("  done_when: reports/debt.md missing\n"))
        assertTrue(text.contains("  last reply: line one line two\n"))
        assertEquals(
            "TASK x INVALID: prompt must be 1-4000 characters\n",
            BackgroundTask.invalidReport("x", "prompt must be 1-4000 characters"),
        )
    }

    @Test
    fun pendingPicksTheFirstSpecWithoutAResult() {
        val dir = tmp.newFolder("proj")
        val tasks = File(dir, BackgroundTask.DIR).apply { mkdirs() }
        File(tasks, "b-2.json").writeText("{}")
        File(tasks, "a-1.json").writeText("{}")
        File(tasks, "a-1.result.txt").writeText("done\n")
        assertEquals("b-2.json", BackgroundTask.pending(dir)?.name)
        File(tasks, "b-2.result.txt").writeText("done\n")
        assertEquals(null, BackgroundTask.pending(dir))
    }

    @Test
    fun doneFileMustBeNonEmptyInsideTheProject() {
        val dir = tmp.newFolder("proj2")
        assertFalse(BackgroundTask.doneFileReady(dir, spec()))
        File(dir, "reports").mkdirs()
        File(dir, "reports/debt.md").writeText("")
        assertFalse(BackgroundTask.doneFileReady(dir, spec()))
        File(dir, "reports/debt.md").writeText("# Debt\n")
        assertTrue(BackgroundTask.doneFileReady(dir, spec()))
    }
}
