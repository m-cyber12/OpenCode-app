package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiGateTest {

    private fun device(
        abis: List<String>,
        sdk: Int = 34,
        release: String = "14",
    ) = AbiGate.DeviceInfo(
        primaryAbi = abis.first(),
        allAbis = abis,
        supportedAbis = abis.filter { it in AbiGate.SUPPORTED_ABIS },
        sdkInt = sdk,
        release = release,
        manufacturer = "Test",
        model = "Emu",
    )

    @Test
    fun arm64IsSupported() {
        val r = AbiGate.evaluateFor(device(listOf("arm64-v8a", "armeabi-v7a")))
        assertTrue(r is AbiGate.Result.Ok)
        assertEquals("arm64-v8a", (r as AbiGate.Result.Ok).abi)
    }

    @Test
    fun x86_64IsNotSupportedSinceTheSwitchOver() {
        // Owner decision 2026-11-16: the release ships arm64 only.
        val r = AbiGate.evaluateFor(device(listOf("x86_64")))
        assertTrue(r is AbiGate.Result.Unsupported)
    }

    @Test
    fun armeabiV7aOnlyIsRejected() {
        val r = AbiGate.evaluateFor(device(listOf("armeabi-v7a")))
        assertTrue(r is AbiGate.Result.Unsupported)
        assertTrue((r as AbiGate.Result.Unsupported).reason.contains("ABI"))
    }

    @Test
    fun x86_32OnlyIsRejected() {
        val r = AbiGate.evaluateFor(device(listOf("x86")))
        assertTrue(r is AbiGate.Result.Unsupported)
    }

    @Test
    fun apiBelow29IsRejectedEvenWithArm64() {
        val r = AbiGate.evaluateFor(device(listOf("arm64-v8a"), sdk = 28, release = "9"))
        assertTrue(r is AbiGate.Result.Unsupported)
        assertTrue((r as AbiGate.Result.Unsupported).reason.contains("API 29"))
    }

    @Test
    fun usesArm64WhenBothAbisPresent() {
        val r = AbiGate.evaluateFor(device(listOf("x86_64", "arm64-v8a")))
        assertEquals("arm64-v8a", (r as AbiGate.Result.Ok).abi) // x86_64 is not shipped
    }
}
