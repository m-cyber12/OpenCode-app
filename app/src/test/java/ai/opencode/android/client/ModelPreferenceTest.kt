package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 10 continuation v4, item 3: "a new chat must start from the model the user
 * actually used, not from whichever provider the server lists first".
 *
 * The defect had two halves, and both are pinned here:
 *
 *  * [DefaultModelHint.resolve] is the decision - a remembered model wins over the
 *    Phase 9 heuristic, and a remembered model whose provider has left the catalog
 *    is ignored rather than sent;
 *  * [ModelPreference] is the memory, and [ModelRefCodec] is its on-disk spelling.
 *    The decoder has to split on the FIRST slash, because a model id may contain
 *    one ("openrouter/openai/gpt-4o-mini" is provider `openrouter`, model
 *    `openai/gpt-4o-mini`) - get that wrong and the app sends a model that does not
 *    exist, which is exactly the class of bug this round is about.
 */
class ModelPreferenceTest {

    private val catalog = linkedMapOf(
        "subconscious" to "subconscious/tim-qwen3.6-27b",
        "openrouter" to "openai/gpt-4o-mini",
        "opencode" to "big-pickle",
    )

    private fun snap(
        connected: List<String> = listOf("opencode"),
        entries: List<OpenCodeApi.ProviderEntry> = emptyList(),
    ) = OpenCodeApi.ProviderSnapshot(
        allIds = catalog.keys.toList(),
        connected = connected,
        defaultModel = catalog,
        entries = entries,
    )

    // ---- the codec ----------------------------------------------------------

    @Test
    fun aModelIdContainingASlashSurvivesTheRoundTrip() {
        val ref = OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini")
        assertEquals("openrouter/openai/gpt-4o-mini", ModelRefCodec.encode(ref))
        assertEquals(ref, ModelRefCodec.decode("openrouter/openai/gpt-4o-mini"))
    }

    @Test
    fun malformedEntriesDecodeToNothingInsteadOfAGuess() {
        assertNull(ModelRefCodec.decode(""))
        assertNull(ModelRefCodec.decode("   "))
        assertNull(ModelRefCodec.decode("no-slash"))
        assertNull(ModelRefCodec.decode("/model-only"))
        assertNull(ModelRefCodec.decode("provider-only/"))
    }

    @Test
    fun theStoredSpellingIsTrimmedOnTheWayIn() {
        assertEquals(OpenCodeApi.ModelRef("google", "gemini-2.5-flash"), ModelRefCodec.decode(" google/gemini-2.5-flash "))
    }

    // ---- the memory ---------------------------------------------------------

    @Test
    fun theLastUsedModelIsWhatComesBackAfterARebuild() {
        val prefs = InMemoryModelPreference()
        prefs.rememberModel(OpenCodeApi.ModelRef("google", "gemini-2.5-flash"))
        assertNull("a memory without a write has no model", InMemoryModelPreference().lastModel())
        assertEquals(
            OpenCodeApi.ModelRef("google", "gemini-2.5-flash"),
            prefs.lastModel(),
        )
    }

    @Test
    fun starringKeepsTheOrderTheUserStarredInAndUnstarringRemoves() {
        val prefs = InMemoryModelPreference()
        val a = OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini")
        val b = OpenCodeApi.ModelRef("google", "gemini-2.5-flash")
        prefs.setStarred(a, true)
        prefs.setStarred(b, true)
        prefs.setStarred(a, true) // starring twice must not duplicate or reorder
        assertEquals(listOf(a, b), prefs.starred())
        prefs.setStarred(a, false)
        assertEquals(listOf(b), prefs.starred())
    }

    // ---- the decision -------------------------------------------------------

    @Test
    fun aRememberedModelBeatsTheFirstConnectedProvider() {
        val hint = DefaultModelHint.resolve(
            persisted = OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini"),
            p = snap(connected = listOf("opencode", "openrouter")),
        )
        assertEquals(OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini"), hint)
    }

    @Test
    fun aRememberedModelThatIsNoLongerInTheCatalogIsIgnored() {
        val hint = DefaultModelHint.resolve(
            persisted = OpenCodeApi.ModelRef("gone", "x/y"),
            p = snap(connected = listOf("opencode")),
        )
        assertEquals(OpenCodeApi.ModelRef("opencode", "big-pickle"), hint)
    }

    @Test
    fun aRememberedModelOnACustomProviderIsAcceptedWithoutADefaultEntry() {
        // A provider the user added by hand has models but no `default` entry in the
        // server's map; the remember path must still work for it.
        val custom = OpenCodeApi.ProviderEntry(
            id = "9router",
            name = "9router",
            models = listOf(OpenCodeApi.ModelEntry(id = "my-model", name = "my-model", status = "")),
        )
        val hint = DefaultModelHint.resolve(
            persisted = OpenCodeApi.ModelRef("9router", "my-model"),
            p = snap(connected = listOf("opencode"), entries = listOf(custom)),
        )
        assertEquals(OpenCodeApi.ModelRef("9router", "my-model"), hint)
    }

    @Test
    fun aRememberedModelWithNoProviderLeftAtAllStillFallsBackToTheHeuristic() {
        val hint = DefaultModelHint.resolve(
            persisted = OpenCodeApi.ModelRef("9router", "my-model"),
            p = snap(connected = listOf("opencode")),
        )
        assertEquals(OpenCodeApi.ModelRef("opencode", "big-pickle"), hint)
    }

    @Test
    fun noMemoryAndNothingConnectedIsNoHintAtAll() {
        assertNull(DefaultModelHint.resolve(persisted = null, p = snap(connected = emptyList())))
    }

    @Test
    fun withoutAMemoryTheHeuristicStillRunsIncludingItsPreferenceArgument() {
        val hint = DefaultModelHint.resolve(
            persisted = null,
            p = snap(connected = listOf("opencode", "openrouter")),
            prefer = "openrouter",
        )
        assertEquals(OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini"), hint)
    }
}
