package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supervisor's restart backoff (Phase 8 test-matrix item: "restart/backoff").
 * Pure function, so the exact schedule - base, growth, cap, jitter band, floor,
 * give-up - is pinned on the JVM; the device exercises the same code through
 * the crash/restart gates (H4/G13 lineage, P8-SERVERKILL in Phase 8).
 */
class RestartBackoffTest {

    @Test
    fun firstRetryLandsBetweenHalfAndOneAndAHalfSeconds() {
        // exp(1) = 1000ms; multiplier 0.5+jitter with jitter in [0,1)
        assertEquals(500L, RestartBackoff.delayMs(1, 0.0))    // 1000 * 0.5
        assertEquals(1000L, RestartBackoff.delayMs(1, 0.5))   // 1000 * 1.0
        assertEquals(1499L, RestartBackoff.delayMs(1, 0.999999)) // 1000 * 1.499999
        assertTrue(RestartBackoff.delayMs(1, 0.75) in 500L..1499L)
    }

    @Test
    fun delayGrowsExponentiallyUntilTheCap() {
        // Fixed jitter (0.0 => exactly 50% of the step) so growth is exact.
        assertEquals(500L, RestartBackoff.delayMs(1, 0.0))   // 1000 * 0.5
        assertEquals(1000L, RestartBackoff.delayMs(2, 0.0))  // 2000 * 0.5
        assertEquals(2000L, RestartBackoff.delayMs(3, 0.0))  // 4000 * 0.5
        assertEquals(4000L, RestartBackoff.delayMs(4, 0.0))  // 8000 * 0.5
        assertEquals(8000L, RestartBackoff.delayMs(5, 0.0))  // 16000 * 0.5
        // Attempt 6: 32000 would exceed the 30s cap, so the step is capped.
        assertEquals(15000L, RestartBackoff.delayMs(6, 0.0)) // 30000 * 0.5
        // Attempts beyond the cap stay at the cap.
        assertEquals(15000L, RestartBackoff.delayMs(7, 0.0))
        assertEquals(15000L, RestartBackoff.delayMs(8, 0.0))
    }

    @Test
    fun jitterBandIsHalfToOneAndAHalfOfTheExponentialStep() {
        for (attempt in 1..8) {
            val exp = minOf(RestartBackoff.CAP_MS, RestartBackoff.BASE_MS shl minOf(attempt - 1, 16))
            val lo = RestartBackoff.delayMs(attempt, 0.0)
            val hi = RestartBackoff.delayMs(attempt, 0.999999)
            // Low end: exactly half the step (clamped up to the floor).
            assertEquals("floor at attempt $attempt", (exp * 0.5).toLong().coerceIn(500, RestartBackoff.CAP_MS), lo)
            // High end: at most 1.5x the step, and never above the cap.
            assertTrue("band at attempt $attempt", lo <= hi && hi <= minOf((exp * 1.5).toLong(), RestartBackoff.CAP_MS))
        }
    }

    @Test
    fun delayIsNeverBelowTheFloorNorAboveTheCap() {
        for (attempt in 1..40) {
            for (j in listOf(0.0, 0.25, 0.5, 0.75, 0.999999)) {
                val d = RestartBackoff.delayMs(attempt, j)
                assertTrue("floor violated (attempt=$attempt jitter=$j): $d", d >= RestartBackoff.FLOOR_MS)
                assertTrue("cap violated (attempt=$attempt jitter=$j): $d", d <= RestartBackoff.CAP_MS)
            }
        }
    }

    @Test
    fun attemptsAreOneBasedAndInvalidInputIsRejected() {
        try {
            RestartBackoff.delayMs(0, 0.5)
            throw AssertionError("attempts=0 must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            RestartBackoff.delayMs(1, -0.1)
            throw AssertionError("negative jitter must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            RestartBackoff.delayMs(1, 1.0)
            throw AssertionError("jitter=1.0 must be rejected (band is [0,1))")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun supervisorGivesUpAfterEightAttempts() {
        for (a in 1 until RestartBackoff.MAX_ATTEMPTS) {
            assertFalse("attempt $a must still retry", RestartBackoff.shouldGiveUp(a))
        }
        assertTrue(RestartBackoff.shouldGiveUp(RestartBackoff.MAX_ATTEMPTS))
        assertTrue(RestartBackoff.shouldGiveUp(RestartBackoff.MAX_ATTEMPTS + 1))
    }

    @Test
    fun totalWorstCaseBackoffBudgetIsBounded() {
        // With maximum jitter the supervisor spends at most this much sleeping
        // before declaring FATAL - the number a user sees as "how long until it
        // gives up". 7 sleeps (attempt 8 gives up without sleeping first):
        // ~1.5s + ~3s + ~6s + ~12s + ~24s + 30s + 30s ~= 106.5s.
        var total = 0L
        for (a in 1 until RestartBackoff.MAX_ATTEMPTS) {
            total += RestartBackoff.delayMs(a, 0.999999)
        }
        assertTrue("worst case is unbounded: $total", total <= 108_000L)
        assertTrue("worst case collapsed: $total", total >= 100_000L)
    }
}
