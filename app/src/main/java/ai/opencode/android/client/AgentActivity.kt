package ai.opencode.android.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the agent is busy with a chat turn, for the runtime notification.
 *
 * Only one repository is cached at a time (AppContainer), and it reports here
 * whenever its busy state changes. [owner] lets a replaced repository clear the
 * flag on its way out, so a stale "busy" cannot outlive it.
 */
object AgentActivity {
    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private var owner: Any? = null
    private var interrupter: (() -> Unit)? = null

    fun report(owner: Any, busy: Boolean, interrupt: () -> Unit) {
        this.owner = owner
        this.interrupter = interrupt
        busyFlow.value = busy
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        interrupter = null
        busyFlow.value = false
    }

    /** Stops the running chat turn(s); the sessions are kept. False when nothing is running. */
    fun pauseTurns(): Boolean {
        val stop = interrupter ?: return false
        if (!busyFlow.value) return false
        stop()
        return true
    }
}
