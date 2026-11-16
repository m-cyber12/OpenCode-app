package ai.opencode.android.task

import ai.opencode.android.task.BackgroundTask.Command
import ai.opencode.android.task.BackgroundTask.Outcome
import ai.opencode.android.task.BackgroundTask.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        @Volatile var aborts = 0
        val aborted: Boolean
            get() = aborts > 0
        @Volatile var prompts = 0
        @Volatile var sessions = 0
        val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
        var blocked: String? = null
        override fun blocker(): String? = blocked
        override fun createSession(title: String): String {
            if (failCreate) throw IllegalStateException("server down")
            sessions++
            return "ses_1"
        }

        override fun prompt(sessionID: String, text: String) {
            prompts++
            texts.add(text)
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
            aborts++
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

    /** Polls until [cond] holds, failing the test after five seconds. */
    private suspend fun waitUntil(cond: () -> Boolean) {
        withTimeout(5_000) {
            while (!cond()) {
                delay(2)
            }
        }
    }

    @Test
    fun aBlockedModelFailsBeforeAnySessionExists() = runBlocking {
        val port = FakePort()
        port.blocked = "No model is selected."
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, pollMs = 1)
        assertEquals(Outcome.FAILED, f.outcome)
        assertEquals(0, port.sessions)
        assertEquals(0, port.prompts)
        assertEquals("", f.sessionID)
        assertTrue(f.error!!.contains("No model is selected"))
    }

    @Test
    fun pauseAbortsTheTurnKeepsTheSessionAndResumeContinuesIt() = runBlocking {
        val port = FakePort(neverIdle = true)
        val control = BackgroundTask.Control()
        val run = async(Dispatchers.Default) {
            BackgroundTask.run(
                spec(), port, doneExists = { false }, control = control,
                limitMs = 60_000, pollMs = 1, graceMs = 50,
            )
        }
        waitUntil { control.status.value == Status.RUNNING }
        assertTrue(control.pause())
        waitUntil { control.status.value == Status.PAUSED }
        assertTrue(port.aborted)
        assertTrue(control.resume())
        waitUntil { port.prompts == 2 }
        assertEquals(BackgroundTask.RESUME_PROMPT, port.texts[1])
        assertEquals(1, port.sessions)
        assertTrue(control.cancel())
        val f = run.await()
        assertEquals(Outcome.CANCELLED, f.outcome)
        assertEquals("ses_1", f.sessionID)
        assertEquals(Status.CANCELLED, control.status.value)
    }

    @Test
    fun pausedTimeDoesNotCountTowardTheTimeLimit() = runBlocking {
        val port = FakePort(neverIdle = true)
        val control = BackgroundTask.Control()
        val run = async(Dispatchers.Default) {
            BackgroundTask.run(
                spec(), port, doneExists = { false }, control = control,
                limitMs = 400, pollMs = 1, graceMs = 50,
            )
        }
        waitUntil { control.status.value == Status.RUNNING }
        assertTrue(control.pause())
        waitUntil { control.status.value == Status.PAUSED }
        // Longer than the whole limit, spent paused: it must not end the task.
        delay(700)
        assertTrue(control.resume())
        delay(100)
        assertFalse(run.isCompleted)
        assertEquals(Status.RUNNING, control.status.value)
        assertTrue(control.cancel())
        assertEquals(Outcome.CANCELLED, run.await().outcome)
    }

    @Test
    fun cancelBeforeStartCreatesNothing() = runBlocking {
        val port = FakePort()
        val control = BackgroundTask.Control()
        assertTrue(control.cancel())
        val f = BackgroundTask.run(spec(), port, doneExists = { false }, control = control, pollMs = 1)
        assertEquals(Outcome.CANCELLED, f.outcome)
        assertEquals(0, port.sessions)
        assertEquals(Status.CANCELLED, control.status.value)
    }

    @Test
    fun controlAcceptsOnlyTheTapsThatFitTheState() {
        val queued = BackgroundTask.Control()
        assertFalse(queued.pause())
        assertFalse(queued.resume())
        assertTrue(queued.cancel())

        val finished = BackgroundTask.Control()
        finished.setStatus(Status.DONE)
        assertFalse(finished.cancel())
        assertFalse(finished.pause())
    }

    @Test
    fun statusesOfferTheTapsThatFitThemAndSayWhetherTheyAreActive() {
        assertEquals(listOf(Command.PAUSE, Command.CANCEL), Status.RUNNING.commands())
        assertEquals(listOf(Command.RESUME, Command.CANCEL), Status.PAUSED.commands())
        assertEquals(listOf(Command.CANCEL), Status.QUEUED.commands())
        for (done in listOf(Status.DONE, Status.INCOMPLETE, Status.TIMED_OUT, Status.FAILED, Status.CANCELLED)) {
            assertEquals(listOf(Command.DISMISS), done.commands())
            assertFalse(done.active)
        }
        assertTrue(Status.QUEUED.active && Status.RUNNING.active && Status.PAUSED.active)
    }

    @Test
    fun everyOutcomeHasTheStatusTheUserSees() {
        assertEquals(Status.DONE, Outcome.DONE.status)
        assertEquals(Status.INCOMPLETE, Outcome.INCOMPLETE.status)
        assertEquals(Status.TIMED_OUT, Outcome.TIMEOUT.status)
        assertEquals(Status.FAILED, Outcome.FAILED.status)
        assertEquals(Status.CANCELLED, Outcome.CANCELLED.status)
        assertEquals(Outcome.CANCELLED, BackgroundTask.decide(doneExists = false, timedOut = false, error = null, cancelled = true))
        // A saved file still wins over a cancel, and an error still wins over everything.
        assertEquals(Outcome.DONE, BackgroundTask.decide(doneExists = true, timedOut = false, error = null, cancelled = true))
        assertEquals(Outcome.FAILED, BackgroundTask.decide(doneExists = true, timedOut = false, error = "x", cancelled = true))
    }

    @Test
    fun theHubKeepsAnActiveTaskOnTheBarAndClearsAFinishedOne() {
        val control = BackgroundTaskHub.begin("debt-1")
        assertEquals("debt-1", BackgroundTaskHub.current.value?.name)
        BackgroundTaskHub.dismiss()
        assertNotNull(BackgroundTaskHub.current.value)
        control.setStatus(Status.DONE)
        BackgroundTaskHub.dismiss()
        assertNull(BackgroundTaskHub.current.value)
    }

    @Test
    fun cancelWhileQueuedIsConsumedOnlyOnceAndOnlyForThatTask() {
        BackgroundTaskHub.setQueued("nightly-1")
        BackgroundTaskHub.cancelQueued()
        // The bar lets go of the queued label at once.
        assertNull(BackgroundTaskHub.queued.value)
        // A different name does not consume it, and the watch loop sees it exactly once.
        assertFalse(BackgroundTaskHub.consumeCancelled("other-task"))
        assertTrue(BackgroundTaskHub.consumeCancelled("nightly-1"))
        assertFalse(BackgroundTaskHub.consumeCancelled("nightly-1"))
        // The result the agent reads says so, in the same TASK <name> form as every other report.
        assertEquals(
            "TASK nightly-1 CANCELLED: cancelled by the user before it started\n",
            BackgroundTask.cancelledBeforeStart("nightly-1"),
        )
    }

    @Test
    fun cancelWithNothingQueuedDoesNothing() {
        BackgroundTaskHub.setQueued(null)
        BackgroundTaskHub.cancelQueued()
        assertFalse(BackgroundTaskHub.consumeCancelled("anything"))
    }
}
