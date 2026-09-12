package ai.opencode.android.runtime

import kotlin.math.min

/**
 * The bounded exponential backoff the supervisor uses between server restarts,
 * extracted as a pure function so the JVM unit tests can pin it (Phase 8
 * test-matrix item: "restart/backoff") instead of it living as inline arithmetic
 * inside [RuntimeManager.backoffOrGiveUp].
 *
 * The formula is the one the supervisor has always used (unchanged):
 *   exp(attempt) = min(CAP, BASE * 2^(attempt-1))     (attempt is 1-based)
 *   sleep        = clamp(exp * (0.5 + jitter), FLOOR, CAP)
 * with jitter in [0, 1) supplied by the caller ([kotlin.random.Random] on the
 * device), so the multiplier is `0.5 + jitter` in [0.5, 1.5) - the delay lands
 * between half and one-and-a-half times the exponential step, then clamps into
 * [FLOOR_MS, CAP_MS]. (The high side is what makes the schedule feel quick on
 * a flaky start without ever exceeding the cap.) Giving up after [MAX_ATTEMPTS]
 * is the caller's decision, made through [shouldGiveUp] so the attempt count
 * and the give-up rule live in one place.
 */
object RestartBackoff {

    const val MAX_ATTEMPTS = 8
    const val BASE_MS = 1000L
    const val CAP_MS = 30_000L
    const val FLOOR_MS = 500L

    /**
     * The sleep in milliseconds before restart attempt [attempts] (1-based) has
     * failed again. [jitter] must be in [0, 1); the multiplier applied is
     * `0.5 + jitter`, i.e. the delay lands in [50%, 150%) of the exponential
     * step before clamping to [FLOOR_MS, CAP_MS].
     */
    fun delayMs(attempts: Int, jitter: Double): Long {
        require(attempts >= 1) { "attempts is 1-based, got $attempts" }
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in [0,1), got $jitter" }
        val exp = min(CAP_MS, BASE_MS shl minOf(attempts - 1, 16))
        return (exp * (0.5 + jitter)).toLong().coerceIn(FLOOR_MS, CAP_MS)
    }

    /** True when the supervisor must stop retrying and report FATAL. */
    fun shouldGiveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
