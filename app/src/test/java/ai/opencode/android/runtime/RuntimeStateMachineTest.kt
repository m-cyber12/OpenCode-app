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


    @Test
    fun coldStartPathIsLegal() {
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STOPPED, RuntimeStatus.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.STARTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.HEALTHY))
    }

    @Test
    fun crashRestartCycleIsLegal() {
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.CRASHED_RESTARTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.STARTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.HEALTHY))
    }

    @Test
    fun retryWithinOneStartAttemptIsLegal() {
        // Launch or health failed, backoff elapsed, same loop tries again.
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.STARTING))
    }

    @Test
    fun userStopIsLegalFromEveryActiveState() {
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.STOPPED))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.STOPPED))
    }

    @Test
    fun giveUpIsLegalFromEveryActiveState() {
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.FATAL))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.FATAL))
    }

    @Test
    fun reEntryFromDeadEndsGoesThroughExtraction() {
        // A new start() re-runs the whole supervisor: ABI gate first, then
        // extraction. There is no fast path from a dead end to HEALTHY.
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.STOPPED, RuntimeStatus.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.UNSUPPORTED_DEVICE, RuntimeStatus.EXTRACTING))
        assertTrue(RuntimeStateMachine.isLegal(RuntimeStatus.FATAL, RuntimeStatus.EXTRACTING))
    }

    @Test
    fun everyStatusKnowsItsLegalTargets() {
        // STOPPED -> STOPPED is the degenerate stop()/start() race at launch
        // (stop() publishes STOPPED before the first EXTRACTING is published).
        assertEquals(
            setOf(RuntimeStatus.EXTRACTING, RuntimeStatus.STOPPED),
            RuntimeStateMachine.expectedFrom(RuntimeStatus.STOPPED),
        )
        assertEquals(
            setOf(RuntimeStatus.STARTING, RuntimeStatus.STOPPED, RuntimeStatus.FATAL),
            RuntimeStateMachine.expectedFrom(RuntimeStatus.EXTRACTING),
        )
        assertEquals(
            setOf(RuntimeStatus.STARTING, RuntimeStatus.HEALTHY, RuntimeStatus.STOPPED, RuntimeStatus.FATAL),
            RuntimeStateMachine.expectedFrom(RuntimeStatus.STARTING),
        )
        assertEquals(
            setOf(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.STOPPED, RuntimeStatus.FATAL),
            RuntimeStateMachine.expectedFrom(RuntimeStatus.HEALTHY),
        )
        assertEquals(
            setOf(RuntimeStatus.STARTING, RuntimeStatus.STOPPED, RuntimeStatus.FATAL),
            RuntimeStateMachine.expectedFrom(RuntimeStatus.CRASHED_RESTARTING),
        )
        // Dead ends only leave through extraction (or nowhere, for an
        // unsupported device that the user never retries).
        assertEquals(setOf(RuntimeStatus.EXTRACTING), RuntimeStateMachine.expectedFrom(RuntimeStatus.FATAL))
        assertEquals(setOf(RuntimeStatus.EXTRACTING), RuntimeStateMachine.expectedFrom(RuntimeStatus.UNSUPPORTED_DEVICE))
        // And the whole table is exactly 17 edges: adding or removing one is a
        // contract change that must be a conscious edit of this test.
        var edges = 0
        for (from in RuntimeStatus.values()) edges += RuntimeStateMachine.expectedFrom(from).size
        assertEquals(17, edges)
    }

    @Test
    fun aCrashCanOnlyReachHealthyThroughStarting() {
        // HEALTHY must never be reached directly from CRASHED_RESTARTING: the
        // process has to be launched and health-checked again.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.HEALTHY))
        assertTrue(RuntimeStateMachine.isLegalVia(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.STARTING, RuntimeStatus.HEALTHY))
    }

    @Test
    fun noStateSkipsExtractionOnReentry() {
        // Stale-payload recovery means every (re)start validates the payload.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.STOPPED, RuntimeStatus.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.STOPPED, RuntimeStatus.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.FATAL, RuntimeStatus.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.FATAL, RuntimeStatus.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.UNSUPPORTED_DEVICE, RuntimeStatus.STARTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.UNSUPPORTED_DEVICE, RuntimeStatus.HEALTHY))
    }

    @Test
    fun impossibleTransitionsAreRejected() {
        // Extraction is done in one pass: it never reports a crash or goes
        // straight to health.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.CRASHED_RESTARTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.HEALTHY))
        // The server is not "crashed" until it was healthy once.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.CRASHED_RESTARTING))
        // Re-entry backwards is not a thing.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.EXTRACTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.EXTRACTING))
        // Dead ends are not published from active states other than FATAL.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.EXTRACTING, RuntimeStatus.UNSUPPORTED_DEVICE))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.STARTING, RuntimeStatus.UNSUPPORTED_DEVICE))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.UNSUPPORTED_DEVICE))
        // A crashed runtime does not "restart" into a stop by itself.
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.CRASHED_RESTARTING, RuntimeStatus.CRASHED_RESTARTING))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.HEALTHY, RuntimeStatus.HEALTHY))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.FATAL, RuntimeStatus.STOPPED))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.UNSUPPORTED_DEVICE, RuntimeStatus.FATAL))
        assertFalse(RuntimeStateMachine.isLegal(RuntimeStatus.UNSUPPORTED_DEVICE, RuntimeStatus.STOPPED))
    }

    @Test
    fun theWholeTableIsClosedAndSelfConsistent() {
        // Every (from, to) pair is either legal or rejected - no ambiguity,
        // and every edge points at a status that exists.
        val all = RuntimeStatus.values()
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
            if (from != RuntimeStatus.UNSUPPORTED_DEVICE && from != RuntimeStatus.FATAL) {
                // Active + STOPPED states all publish; their target set must be
                // non-empty (UNSUPPORTED_DEVICE/FATAL are set outside publish()
                // and only re-enter through a fresh start).
                assertTrue("$from must have legal targets", RuntimeStateMachine.expectedFrom(from).isNotEmpty())
            }
        }
    }
}
