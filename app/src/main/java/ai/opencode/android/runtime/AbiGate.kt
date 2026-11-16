package ai.opencode.android.runtime

import android.os.Build

/**
 * ABI / device gating (phase scope item).
 *
 * The embedded runtime is native code: Node 26 and the Bionic tools are shipped
 * for `arm64-v8a` only (owner decision 2026-11-16: x86_64 dropped from the
 * release). There is no 32-bit build, so devices whose primary ABI is
 * armeabi-v7a/x86/x86_64 cannot run the agent. Rather than failing
 * opaquely (a exec format error at launch), the app reports a clear,
 * actionable unsupported state.
 */
object AbiGate {

    /** ABIs the runtime payload ships for. */
    val SUPPORTED_ABIS = setOf("arm64-v8a")

    data class DeviceInfo(
        val primaryAbi: String,
        val allAbis: List<String>,
        val supportedAbis: List<String>,
        val sdkInt: Int,
        val release: String,
        val manufacturer: String,
        val model: String,
    )

    sealed class Result {
        /** A usable runtime ABI exists on this device. [abi] is the payload dir name. */
        data class Ok(val abi: String, val device: DeviceInfo) : Result()

        /** Device cannot run the embedded runtime. [reason] is user-facing. */
        data class Unsupported(val reason: String, val device: DeviceInfo) : Result()
    }

    fun evaluate(): Result {
        val abis = Build.SUPPORTED_ABIS?.toList()?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Build.CPU_ABI?.takeIf { it.isNotBlank() } ?: "unknown")
        val device = DeviceInfo(
            primaryAbi = abis.first(),
            allAbis = abis,
            supportedAbis = abis.filter { it in SUPPORTED_ABIS },
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "?",
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
        )
        return evaluateFor(device)
    }

    /** Pure decision logic (unit-testable without Android). */
    fun evaluateFor(device: DeviceInfo): Result {
        // minSdk is 29 so the OS enforces this at install time too; checked
        // here for a clean diagnostic instead of a native crash.
        if (device.sdkInt < 29) {
            return Result.Unsupported(
                "OpenCode requires Android 10 (API 29) or newer; this device runs API ${device.sdkInt}.",
                device,
            )
        }
        val abi = device.supportedAbis.firstOrNull()
        return if (abi != null) {
            Result.Ok(abi, device)
        } else {
            Result.Unsupported(
                "OpenCode runs on 64-bit Arm devices (arm64-v8a). " +
                    "This device's ABI (${device.primaryAbi}) is not supported.",
                device,
            )
        }
    }
}
