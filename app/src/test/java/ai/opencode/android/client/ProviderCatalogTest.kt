package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 continuation v4, item 2: "search the catalog, tap a provider, type only
 * the key - and keep a separate manual path for endpoints no catalog knows".
 *
 * Both halves are pure Kotlin by construction ([ProviderCatalog],
 * [CustomProviderConfig]) so the ranking and the config document are pinned by
 * JVM tests instead of by a device run: a search that puts an exact match below a
 * substring match is a bug the user feels on every keystroke, and a custom provider
 * that writes the wrong config shape fails far away from the field that caused it.
 */
class ProviderCatalogTest {

    private fun entry(id: String, name: String) =
        OpenCodeApi.ProviderEntry(id = id, name = name, models = listOf(OpenCodeApi.ModelEntry(id = "$id-model", name = "$id model", status = "")))

    private val catalog = listOf(
        entry("9router", "9router"),
        entry("anthropic", "Anthropic"),
        entry("google", "Google"),
        entry("openrouter", "OpenRouter"),
        entry("opencode", "OpenCode"),
        entry("subconscious", "Subconscious"),
        entry("google-vertex", "Google Vertex AI"),
    )

    @Test
    fun anExactIdWinsOverEverythingElse() {
        val hits = ProviderCatalog.search(catalog, "google")
        assertEquals(listOf("google", "google-vertex"), hits.map { it.id })
    }

    @Test
    fun anIdPrefixBeatsANameMatch() {
        val hits = ProviderCatalog.search(catalog, "open")
        // `opencode` and `openrouter` are id prefixes; both come before any name
        // match, and they are ordered by name so the list does not reshuffle.
        assertEquals(listOf("opencode", "openrouter"), hits.map { it.id })
    }

    @Test
    fun caseDoesNotMatterAndSubstringsStillMatch() {
        assertEquals(listOf("anthropic"), ProviderCatalog.search(catalog, "ANTHRO").map { it.id })
        assertEquals(listOf("subconscious"), ProviderCatalog.search(catalog, "on").map { it.id })
    }

    @Test
    fun aQueryNothingMatchesReturnsNothingRatherThanTheWholeCatalog() {
        assertTrue(ProviderCatalog.search(catalog, "zzz").isEmpty())
    }

    @Test
    fun anEmptyQueryReturnsTheCatalogInItsOwnOrder() {
        assertEquals(catalog.map { it.id }, ProviderCatalog.search(catalog, "  ").map { it.id })
    }

    @Test
    fun theResultIsBoundedSoASingleLetterCannotComposeHundredsOfRows() {
        val many = (1..200).map { entry("provider-$it", "Provider $it") }
        assertEquals(60, ProviderCatalog.search(many, "provider").size)
        assertEquals(5, ProviderCatalog.search(many, "provider", limit = 5).size)
    }

    // ---- the manual path ----------------------------------------------------

    @Test
    fun aProviderIdMustBeASlugBecauseItBecomesAConfigKey() {
        assertTrue(CustomProviderConfig.isUsableId("9router"))
        assertTrue(CustomProviderConfig.isUsableId("my-endpoint.local"))
        assertFalse("uppercase would not be the id the user typed", CustomProviderConfig.isUsableId("NineRouter"))
        assertFalse(CustomProviderConfig.isUsableId(""))
        assertFalse(CustomProviderConfig.isUsableId("has space"))
        assertFalse(CustomProviderConfig.isUsableId("-leading-dash"))
    }

    @Test
    fun modelIdsAreSplitDedupedAndKeptInOrder() {
        assertEquals(
            listOf("a", "b", "c"),
            CustomProviderConfig.parseModels("a, b\nc  a"),
        )
        assertTrue(CustomProviderConfig.parseModels("  ,  \n ").isEmpty())
    }

    @Test
    fun theConfigDocumentIsTheShapeUpstreamExpects() {
        val patch = CustomProviderConfig.patchFor(
            providerID = "9router",
            displayName = "9router",
            baseUrl = "http://192.168.1.10:20128/v1",
            models = listOf("my-model"),
        )!!
        val provider = patch.getJSONObject("provider").getJSONObject("9router")
        assertEquals(CustomProviderConfig.NPM, provider.getString("npm"))
        assertEquals("9router", provider.getString("name"))
        assertEquals("http://192.168.1.10:20128/v1", provider.getJSONObject("options").getString("baseURL"))
        assertTrue(provider.getJSONObject("models").has("my-model"))
    }

    @Test
    fun impossibleInputProducesNoDocumentInsteadOfABrokenOne() {
        assertNull("bad id", CustomProviderConfig.patchFor("Bad Id", "x", "http://h/v1", listOf("m")))
        assertNull("no url", CustomProviderConfig.patchFor("ok", "x", "", listOf("m")))
        assertNull("not a url", CustomProviderConfig.patchFor("ok", "x", "192.168.1.10:20128", listOf("m")))
        assertNull("no models", CustomProviderConfig.patchFor("ok", "x", "http://h/v1", emptyList()))
    }

    @Test
    fun aBlankDisplayNameFallsBackToTheIdRatherThanAnEmptyLabel() {
        val patch = CustomProviderConfig.patchFor("9router", "   ", "http://h/v1", listOf("m"))!!
        assertEquals("9router", patch.getJSONObject("provider").getJSONObject("9router").getString("name"))
    }
}
