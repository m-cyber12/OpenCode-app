package ai.opencode.android.runtime

/**
 * The supervisor's state machine, made explicit and pure so the JVM unit tests
 * can pin the contract (Phase 8 test-matrix item: "runtime state machine").
 *
 * [RuntimeManager.publish] consults this table on every transition: a legal
 * transition is just logged (as before), an illegal one is logged as
 * `ILLEGAL_STATE_TRANSITION` so a supervisor defect is loud in runtime.log
 * instead of visible only as a confused UI. The table is a *mirror* of the
 * supervisor's behaviour - the supervisor does not branch on it - so adding an
 * entry here can never change runtime behaviour; it only makes the expected
 * shape testable and diffable.
 *
 * The legal graph (every edge is produced by a code path in RuntimeManager):
 *
 *   STOPPED ───────────────► EXTRACTING ─► STARTING ─► HEALTHY
 *     ▲  (start)              │  │           │  │  ▲       │ │
 *     │                       │  │           │  │  │       │ │
 *     │                       ▼  │           │  │  │       ▼ ▼
 *     │                     (fatal)          │  │  │  CRASHED_RESTARTING
 *     │                       │              │  │  │       │
 *     │                        ▼             ▼  │  │       │
 *     └──────── FATAL ◄────────┴── (give up) ──┘  └── (exit)
 *
 *   * STOPPED → EXTRACTING: a fresh start() (cold launch or restart button).
 *   * UNSUPPORTED_DEVICE → EXTRACTING: the user re-tries start() on a device
 *     the gate once rejected (the gate is re-evaluated on every start).
 *   * FATAL → EXTRACTING: resetAndRestart() (the Settings "restart" action).
 *   * STARTING → STARTING: a launch/health failure, then a bounded-backoff
 *     retry inside the same supervisor loop.
 *   * any active state → STOPPED: stop() or the watch loop observing a
 *     generation change while the server is shutting down.
 *   * any active state → FATAL: extraction failure, backoff exhausted,
 *     supervisor exception, or the fail-closed loopback-policy stop.
 *
 * Terminal-ish states are not truly terminal (a new start/reset re-enters at
 * EXTRACTING), which is exactly what the Welcome screen offers.
 */
object RuntimeStateMachine {

    private val LEGAL: Set<Pair<RuntimeStatus, RuntimeStatus>> = setOf(
        // fresh starts (cold launch, retry from a dead end)
        RuntimeStatus.STOPPED to RuntimeStatus.EXTRACTING,
        RuntimeStatus.UNSUPPORTED_DEVICE to RuntimeStatus.EXTRACTING,
        RuntimeStatus.FATAL to RuntimeStatus.EXTRACTING,
        // the happy path
        RuntimeStatus.EXTRACTING to RuntimeStatus.STARTING,
        RuntimeStatus.STARTING to RuntimeStatus.HEALTHY,
        // crash + bounded-backoff restart
        RuntimeStatus.HEALTHY to RuntimeStatus.CRASHED_RESTARTING,
        RuntimeStatus.CRASHED_RESTARTING to RuntimeStatus.STARTING,
        // retry inside one start attempt (launch or health failed)
        RuntimeStatus.STARTING to RuntimeStatus.STARTING,
        // user stops / generation invalidation from any active state
        RuntimeStatus.EXTRACTING to RuntimeStatus.STOPPED,
        RuntimeStatus.STARTING to RuntimeStatus.STOPPED,
        RuntimeStatus.HEALTHY to RuntimeStatus.STOPPED,
        RuntimeStatus.CRASHED_RESTARTING to RuntimeStatus.STOPPED,
        // degenerate but reachable: stop() publishes STOPPED while the state
        // has not moved past STOPPED yet (start/stop race at launch)
        RuntimeStatus.STOPPED to RuntimeStatus.STOPPED,
        // give-up / hard failures from any active state
        RuntimeStatus.EXTRACTING to RuntimeStatus.FATAL,
        RuntimeStatus.STARTING to RuntimeStatus.FATAL,
        RuntimeStatus.HEALTHY to RuntimeStatus.FATAL,
        RuntimeStatus.CRASHED_RESTARTING to RuntimeStatus.FATAL,
    )

    /** True when moving [from] to [to] is part of the supervisor contract. */
    fun isLegal(from: RuntimeStatus, to: RuntimeStatus): Boolean =
        (from to to) in LEGAL

    /** Human-readable explanation for an illegal pair (diagnostics, tests). */
    fun expectedFrom(from: RuntimeStatus): Set<RuntimeStatus> =
        LEGAL.filter { it.first == from }.map { it.second }.toSet()

    /**
     * Whether [to] is reachable from [from] in one step, or only after passing
     * through [via]. Used by tests to document the intended path instead of
     * guessing - e.g. FATAL only leaves through EXTRACTING, and CRASHED_
     * RESTARTING only reaches HEALTHY through STARTING.
     */
    fun isLegalVia(from: RuntimeStatus, via: RuntimeStatus, to: RuntimeStatus): Boolean =
        isLegal(from, via) && isLegal(via, to)
}
