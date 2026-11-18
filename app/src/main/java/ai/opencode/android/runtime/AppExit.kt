package ai.opencode.android.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user chose Exit on the runtime notification. The agent is stopped, and the
 * app's screen closes with it, so Exit means what it says instead of leaving an
 * open window that reports the agent as failed.
 */
object AppExit {
    private val requestedFlow = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = requestedFlow.asStateFlow()

    fun request() {
        requestedFlow.value = true
    }

    /** A fresh launch starts with no exit pending. */
    fun reset() {
        requestedFlow.value = false
    }
}
