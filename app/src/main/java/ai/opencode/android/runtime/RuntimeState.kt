package ai.opencode.android.runtime

/** Observable lifecycle states for the embedded runtime (consumed by the UI). */
enum class RuntimeStatus {
    UNSUPPORTED_DEVICE,
    EXTRACTING,
    STARTING,
    HEALTHY,
    CRASHED_RESTARTING,
    STOPPED,
    FATAL,
}

data class RuntimeState(
    val status: RuntimeStatus,
    val detail: String = "",
    val abi: String? = null,
    val restartCount: Int = 0,
    val sinceMs: Long = System.currentTimeMillis(),
    val manifest: RuntimeManifest? = null,
    val device: AbiGate.DeviceInfo? = null,
)
