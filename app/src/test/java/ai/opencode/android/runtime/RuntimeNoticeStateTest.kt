package ai.opencode.android.runtime

import ai.opencode.android.runtime.RuntimeNoticeState.Kind
import ai.opencode.android.runtime.RuntimeNoticeState.Tap
import ai.opencode.android.task.BackgroundTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runtime notification table: which status shows, and which taps it offers. */
class RuntimeNoticeStateTest {

    private fun kind(runtime: RuntimeStatus, task: BackgroundTask.Status? = null) =
        RuntimeNoticeState.kindOf(runtime, task)

    @Test
    fun aHealthyAgentWithNothingRunningIsReadyWithExitOnly() {
        assertEquals(Kind.READY, kind(RuntimeStatus.HEALTHY))
        // A queued task has not started yet: the agent is still ready.
        assertEquals(Kind.READY, kind(RuntimeStatus.HEALTHY, BackgroundTask.Status.QUEUED))
        assertEquals(listOf(Tap.EXIT), RuntimeNoticeState.tapsOf(Kind.READY))
    }

    @Test
    fun aRunningBackgroundTaskIsWorkingWithPauseAndExit() {
        assertEquals(Kind.WORKING, kind(RuntimeStatus.HEALTHY, BackgroundTask.Status.RUNNING))
        assertEquals(listOf(Tap.PAUSE, Tap.EXIT), RuntimeNoticeState.tapsOf(Kind.WORKING))
    }

    @Test
    fun aPausedBackgroundTaskIsPausedWithResumeAndExit() {
        assertEquals(Kind.PAUSED, kind(RuntimeStatus.HEALTHY, BackgroundTask.Status.PAUSED))
        assertEquals(listOf(Tap.RESUME, Tap.EXIT), RuntimeNoticeState.tapsOf(Kind.PAUSED))
    }

    @Test
    fun aStoppedOrRestartingAgentIsErrorWithExitOnly() {
        assertEquals(Kind.ERROR, kind(RuntimeStatus.FATAL))
        assertEquals(Kind.ERROR, kind(RuntimeStatus.CRASHED_RESTARTING))
        assertEquals(Kind.ERROR, kind(RuntimeStatus.UNSUPPORTED_DEVICE))
        assertEquals(listOf(Tap.EXIT), RuntimeNoticeState.tapsOf(Kind.ERROR))
    }

    @Test
    fun anAgentStillComingUpIsStartingWithExitOnly() {
        assertEquals(Kind.STARTING, kind(RuntimeStatus.EXTRACTING))
        assertEquals(Kind.STARTING, kind(RuntimeStatus.STARTING))
        assertEquals(Kind.STARTING, kind(RuntimeStatus.STOPPED))
        assertEquals(listOf(Tap.EXIT), RuntimeNoticeState.tapsOf(Kind.STARTING))
    }

    @Test
    fun everyStateOffersExitSoTheUserCanAlwaysStopTheRuntime() {
        for (kind in Kind.values()) {
            assertTrue("$kind must offer Exit", RuntimeNoticeState.tapsOf(kind).contains(Tap.EXIT))
        }
    }

    @Test
    fun aTaskStatusNeverChangesAnUnhealthyRuntime() {
        // Pause must not show while the agent is down: the task cannot run.
        for (task in BackgroundTask.Status.values()) {
            assertEquals(Kind.ERROR, kind(RuntimeStatus.FATAL, task))
        }
    }
}
