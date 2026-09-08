package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supervisor state machine contract (Phase 8 test-matrix item: "runtime
 * state machine"). [RuntimeManager.publish] consults [RuntimeStateMachine] on
 * every transition and logs `ILLEGAL_STATE_TRANSITION` on a violation, so this
 * table is the authoritative, JVM-pinned shape of the lifecycle:
 *
 *   cold start   : STOPPED -> EXTRACTING -> STARTING -> HEALTHY
 *   crash        : HEALTHY -> CRASHED_RESTARTING -> STARTING -> HEALTHY
 *   retry        : STARTING -> STARTING (launch/health failed, bounded backoff)
 *   user stop    : any active state -> STOPPED
 *   give-up      : any active state -> FATAL (backoff exhausted, extraction
 *                  failure, supervisor error, loopback-policy violation)
 *   re-entry     : STOPPED / UNSUPPORTED_DEVICE / FATAL -> EXTRACTING (a new
 *                  start() or resetAndRestart() re-evaluates everything)
 */
class RuntimeStateMachineTest {

    private val S = RuntimeStatus

    @Test
    fun coldStartPathIsLegal() {
        assertTrue(RuntimeStateMachine.isLegal(S.STOPPED, S.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(S.EXTRACTING, S.STARTING))
        assertTrue(RuntimeStateMachine.isLegal(S.STARTING, S.HEALTHY))
    }

    @Test
    fun crashRestartCycleIsLegal() {
        assertTrue(RuntimeStateMachine.isLegal(S.HEALTHY, S.CRASHED_RESTARTING))
        assertTrue(RuntimeStateMachine.isLegal(S.CRASHED_RESTARTING, S.STARTING))
        assertTrue(RuntimeStateMachine.isLegal(S.STARTING, S.HEALTHY))
    }

    @Test
    fun retryWithinOneStartAttemptIsLegal() {
        // Launch or health failed, backoff elapsed, same loop tries again.
        assertTrue(RuntimeStateMachine.isLegal(S.STARTING, S.STARTING))
    }

    @Test
    fun userStopIsLegalFromEveryActiveState() {
        assertTrue(RuntimeStateMachine.isLegal(S.EXTRACTING, S.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(S.STARTING, S.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(S.HEALTHY, S.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(S.CRASHED_RESTARTING, S.STOPPED))
    }

    @Test
    fun giveUpIsLegalFromEveryActiveState() {
        assertTrue(RuntimeStateMachine.isLegal(S.EXTRACTING, S.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(S.STARTING, S.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(S.HEALTHY, S.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(S.CRASHED_RESTARTING, S.FATAL))
    }

    @Test
    fun reEntryFromDeadEndsGoesThroughExtraction() {
        // A new start() re-runs the whole supervisor: ABI gate first, then
        // extraction. There is no fast path from a dead end to HEALTHY.
        assertTrue(RuntimeStateMachine.isLegal(S.STOPPED, S.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(S.UNSUPPORTED_DEVICE, S.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(S.FATAL, S.EXTRACTING))
    }

    @Test
    fun everyStatusKnowsItsLegalTargets() {
        // STOPPED -> STOPPED is the degenerate stop()/start() race at launch
        // (stop() publishes STOPPED before the first EXTRACTING is published).
        assertEquals(
            setOf(S.EXTRACTING, S.STOPPED),
            RuntimeStateMachine.expectedFrom(S.STOPPED),
        )
        assertEquals(
            setOf(S.STARTING, S.STOPPED, S.FATAL),
            RuntimeStateMachine.expectedFrom(S.EXTRACTING),
        )
        assertEquals(
            setOf(S.STARTING, S.HEALTHY, S.STOPPED, S.FATAL),
            RuntimeStateMachine.expectedFrom(S.STARTING),
        )
        assertEquals(
            setOf(S.CRASHED_RESTARTING, S.STOPPED, S.FATAL),
            RuntimeStateMachine.expectedFrom(S.HEALTHY),
        )
        assertEquals(
            setOf(S.STARTING, S.STOPPED, S.FATAL),
            RuntimeStateMachine.expectedFrom(S.CRASHED_RESTARTING),
        )
        // Dead ends only leave through extraction (or nowhere, for an
        // unsupported device that the user never retries).
        assertEquals(setOf(S.EXTRACTING), RuntimeStateMachine.expectedFrom(S.FATAL))
        assertEquals(setOf(S.EXTRACTING), RuntimeStateMachine.expectedFrom(S.UNSUPPORTED_DEVICE))
        // And the whole table is exactly 17 edges: adding or removing one is a
        // contract change that must be a conscious edit of this test.
        var edges = 0
        for (from in S.values()) edges += RuntimeStateMachine.expectedFrom(from).size
        assertEquals(17, edges)
    }

    @Test
    fun aCrashCanOnlyReachHealthyThroughStarting() {
        // HEALTHY must never be reached directly from CRASHED_RESTARTING: the
        // process has to be launched and health-checked again.
        assertFalse(RuntimeStateMachine.isLegal(S.CRASHED_RESTARTING, S.HEALTHY))
        assertTrue(RuntimeStateMachine.isLegalVia(S.CRASHED_RESTARTING, S.STARTING, S.HEALTHY))
    }

    @Test
    fun noStateSkipsExtractionOnReentry() {
        // Stale-payload recovery means every (re)start validates the payload.
        assertFalse(RuntimeStateMachine.isLegal(S.STOPPED, S.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(S.STOPPED, S.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(S.FATAL, S.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(S.FATAL, S.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(S.UNSUPPORTED_DEVICE, S.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(S.UNSUPPORTED_DEVICE, S.HEALTHY))
    }

    @Test
    fun impossibleTransitionsAreRejected() {
        // Extraction is done in one pass: it never reports a crash or goes
        // straight to health.
        assertFalse(RuntimeStateMachine.isLegal(S.EXTRACTING, S.CRASHED_RESTARTING))
        assertFalse(RuntimeStateMachine.isLegal(S.EXTRACTING, S.HEALTHY))
        // The server is not "crashed" until it was healthy once.
        assertFalse(RuntimeStateMachine.isLegal(S.STARTING, S.CRASHED_RESTARTING))
        // Re-entry backwards is not a thing.
        assertFalse(RuntimeStateMachine.isLegal(S.HEALTHY, S.EXTRACTING))
        assertFalse(RuntimeStateMachine.isLegal(S.STARTING, S.EXTRACTING))
        // Dead ends are not published from active states other than FATAL.
        assertFalse(RuntimeStateMachine.isLegal(S.EXTRACTING, S.UNSUPPORTED_DEVICE))
        assertFalse(RuntimeStateMachine.isLegal(S.STARTING, S.UNSUPPORTED_DEVICE))
        assertFalse(RuntimeStateMachine.isLegal(S.HEALTHY, S.UNSUPPORTED_DEVICE))
        // A crashed runtime does not "restart" into a stop by itself.
        assertFalse(RuntimeStateMachine.isLegal(S.CRASHED_RESTARTING, S.CRASHED_RESTARTING))
        assertFalse(RuntimeStateMachine.isLegal(S.HEALTHY, S.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(S.FATAL, S.STOPPED))
        assertFalse(RuntimeStateMachine.isLegal(S.UNSUPPORTED_DEVICE, S.FATAL))
        assertFalse(RuntimeStateMachine.isLegal(S.UNSUPPORTED_DEVICE, S.STOPPED))
    }

    @Test
    fun theWholeTableIsClosedAndSelfConsistent() {
        // Every (from, to) pair is either legal or rejected - no ambiguity,
        // and every edge points at a status that exists.
        val all = S.values()
        for (from in all) {
            for (to in all) {
                val legal = RuntimeStateMachine.isLegal(from, to)
                val listed = RuntimeStateMachine.expectedFrom(from).contains(to)
                assertEquals("table disagrees with itself for $from -> $to", legal, listed)
            }
        }
        // The supervisor has exactly 7 states; the table must cover all of them
        // as sources (a new status added to the enum without table entries
        // would show up here as an empty source set and fail the test).
        for (from in all) {
            if (from != S.UNSUPPORTED_DEVICE && from != S.FATAL) {
                // Active + STOPPED states all publish; their target set must be
                // non-empty (UNSUPPORTED_DEVICE/FATAL are set outside publish()
                // and only re-enter through a fresh start).
                assertTrue("$from must have legal targets", RuntimeStateMachine.expectedFrom(from).isNotEmpty())
            }
        }
    }
}
