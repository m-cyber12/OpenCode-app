package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Phase 9 fix for the "configured provider is never used" defect:
 * the client must never default the composer to a provider the server does not
 * report as connected (Phase 8 shipped `subconscious/...` as the default and
 * every turn fell back to the bundled `opencode` provider).
 */
class DefaultModelHintTest {

    private fun snap(connected: List<String>, defaults: Map<String, String>) =
        OpenCodeApi.ProviderSnapshot(allIds = defaults.keys.toList(), connected = connected, defaultModel = defaults)

    private val catalog = linkedMapOf(
        "subconscious" to "subconscious/tim-qwen3.6-27b",
        "anthropic" to "claude-sonnet-4",
        "openrouter" to "openai/gpt-4o-mini",
        "google" to "gemini-2.5-flash",
        "opencode" to "big-pickle",
    )

    @Test
    fun firstCatalogEntryIsNeverChosenWhenNotConnected() {
        val hint = DefaultModelHint.pick(snap(connected = listOf("opencode"), defaults = catalog))
        assertEquals(OpenCodeApi.ModelRef("opencode", "big-pickle"), hint)
    }

    @Test
    fun aConnectedThirdPartyProviderBeatsTheBundledOne() {
        val hint = DefaultModelHint.pick(snap(connected = listOf("opencode", "openrouter"), defaults = catalog))
        assertEquals(OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini"), hint)
    }

    @Test
    fun theJustProvisionedProviderWinsWhenConnected() {
        val hint = DefaultModelHint.pick(snap(connected = listOf("opencode", "openrouter", "google"), defaults = catalog), prefer = "google")
        assertEquals(OpenCodeApi.ModelRef("google", "gemini-2.5-flash"), hint)
    }

    @Test
    fun aPreferredProviderTheServerDoesNotReportConnectedIsNotForced() {
        // The server's own check decides "connected"; we do not override it.
        val hint = DefaultModelHint.pick(snap(connected = listOf("opencode"), defaults = catalog), prefer = "google")
        assertEquals(OpenCodeApi.ModelRef("opencode", "big-pickle"), hint)
    }

    @Test
    fun nothingConnectedMeansNoHintNotAnArbitraryProvider() {
        assertNull(DefaultModelHint.pick(snap(connected = emptyList(), defaults = catalog)))
    }

    @Test
    fun aConnectedProviderWithoutADefaultModelIsSkipped() {
        val hint = DefaultModelHint.pick(snap(connected = listOf("weird", "opencode"), defaults = catalog))
        assertEquals(OpenCodeApi.ModelRef("opencode", "big-pickle"), hint)
    }
}
