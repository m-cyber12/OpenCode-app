package ai.opencode.android.ui.common

import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.UiError
import ai.opencode.android.runtime.RuntimeState
import ai.opencode.android.runtime.RuntimeStatus
import ai.opencode.android.runtime.RuntimeVersion

/**
 * A screen-safe view of the runtime supervisor's state.
 *
 * Screens never see [RuntimeState] itself, and they certainly never reach for the
 * runtime singleton (a static check enforces that only `ui/AppRoot.kt` may): what a
 * screen gets is this value type, which is trivially fabricable in a Compose UI
 * test. That is what makes the Phase 6 UI gates deterministic - a screen can be
 * rendered in every degraded state without a device runtime, a model or a key.
 *
 * Nothing here is invented either: [availability] is [UiError.classifyRuntimeStatus]
 * applied to the supervisor's own status name, and every other field is one the
 * runtime already reports (`detail`, `restartCount`, `manifest`, `device`,
 * `integration`).
 */
data class RuntimeSummary(
    val status: String,
    val detail: String,
    val restartCount: Int,
    val availability: AgentAvailability,
    val ready: Boolean,
    val opencodeVersion: String,
    val payloadVersion: Int,
    val abi: String,
    val device: String,
    val androidVersion: String,
    /** The supervisor's own loopback-bind + credential audit line. */
    val audit: String,
) {
    companion object {
        /** Before the supervisor has reported anything: starting, not broken. */
        val UNKNOWN = RuntimeSummary(
            status = "",
            detail = "",
            restartCount = 0,
            availability = AgentAvailability.RUNTIME_STARTING,
            ready = false,
            opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
            payloadVersion = RuntimeVersion.PAYLOAD_VERSION,
            abi = "",
            device = "",
            androidVersion = "",
            audit = "",
        )
    }
}

/** Map the supervisor's state onto the value type screens consume. */
fun RuntimeState.toSummary(): RuntimeSummary {
    val dev = device
    return RuntimeSummary(
        status = status.name,
        detail = detail,
        restartCount = restartCount,
        availability = UiError.classifyRuntimeStatus(status.name),
        ready = status == RuntimeStatus.HEALTHY,
        opencodeVersion = manifest?.opencodeVersion?.takeIf { it.isNotEmpty() } ?: RuntimeVersion.OPENCODE_VERSION,
        payloadVersion = manifest?.payloadVersion ?: RuntimeVersion.PAYLOAD_VERSION,
        abi = abi ?: dev?.primaryAbi ?: "",
        device = if (dev == null) "" else "${dev.manufacturer} ${dev.model}".trim(),
        androidVersion = if (dev == null) "" else "${dev.release} (API ${dev.sdkInt})",
        audit = integration,
    )
}
