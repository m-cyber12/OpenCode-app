package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.opencode.android.client.v2.JsoncEdit

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
    fun onlyABlankIdIsRefusedHereEverythingElseGoesToTheServer() {
        assertNull("no id", CustomProviderConfig.patchFor("  ", "x", "", emptyList()))
        assertNotNull("bad id is sent, the server judges it", CustomProviderConfig.patchFor("Bad Id", "x", "192.168.1.10:20128", listOf("m")))
        assertNotNull("no models is sent too", CustomProviderConfig.patchFor("ok", "x", "", emptyList()))
    }

    @Test
    fun aBlankDisplayNameFallsBackToTheIdRatherThanAnEmptyLabel() {
        val patch = CustomProviderConfig.patchFor("9router", "   ", "http://h/v1", listOf("m"))!!
        assertEquals("9router", patch.getJSONObject("provider").getJSONObject("9router").getString("name"))
    }

    /**
     * 2026-11-21 (owner): "I fill every field and tap Add and nothing happens". The config writer
     * had no `provider` case, so every add threw 501 and the message went to a screen that does not
     * show it. These pin the document the writer now produces, and the reason for each refusal.
     */
    @Test
    fun theProviderBlockIsWrittenIntoTheConfigAndKeepsOtherProviders() {
        val text = "{\n  // my note\n  \"provider\": {\n    \"other\": { \"npm\": \"x\" }\n  }\n}\n"
        val patch = CustomProviderConfig.patchFor("9router", "9router", "http://h:20128/v1", listOf("my-model"))!!
        val merged = CustomProviderConfig.mergeProviders(
            org.json.JSONObject(JsoncEdit.stripComments(text)).optJSONObject("provider"),
            patch.getJSONObject("provider"),
        )
        val written = JsoncEdit.withKey(text, "provider", merged.toString(2))
        val back = JsoncEdit.parseObject(written).getJSONObject("provider")
        assertTrue("the existing provider survives", back.has("other"))
        assertEquals("http://h:20128/v1", back.getJSONObject("9router").getJSONObject("options").getString("baseURL"))
        assertTrue(back.getJSONObject("9router").getJSONObject("models").has("my-model"))
        assertTrue("the user's comment survives", written.contains("// my note"))
    }

    @Test
    fun aSecondAddReplacesOnlyItsOwnId() {
        val existing = org.json.JSONObject().put("9router", org.json.JSONObject().put("name", "old"))
        val added = org.json.JSONObject().put("local", org.json.JSONObject().put("name", "local"))
        val merged = CustomProviderConfig.mergeProviders(existing, added)
        assertEquals("old", merged.getJSONObject("9router").getString("name"))
        assertEquals("local", merged.getJSONObject("local").getString("name"))
    }

    /** The custom providers are read back from the config, so Edit and Remove work on what the file holds. */
    @Test
    fun savedCustomProvidersAreReadBackFromTheConfigAndOthersAreIgnored() {
        val text = """
            {
              // the user's note
              "provider": {
                "9router": {
                  "npm": "@ai-sdk/openai-compatible",
                  "name": "9router",
                  "options": { "baseURL": "http://h:20128/v1" },
                  "models": { "my-model": { "name": "my-model" } }
                },
                "openrouter": { "npm": "@openrouter/ai-sdk-provider" }
              }
            }
        """.trimIndent()
        val saved = CustomProviderConfig.savedFrom(text)
        assertEquals(1, saved.size)
        assertEquals("9router", saved[0].id)
        assertEquals("http://h:20128/v1", saved[0].baseUrl)
        assertEquals(listOf("my-model"), saved[0].models)
    }

    @Test
    fun removingOneProviderKeepsTheOthersAndTheComments() {
        val text = "{\n  // keep me\n  \"provider\": {\n    \"9router\": { \"npm\": \"${CustomProviderConfig.NPM}\" },\n    \"local\": { \"npm\": \"${CustomProviderConfig.NPM}\" }\n  }\n}\n"
        val after = CustomProviderConfig.withoutProvider(text, "9router")
        val providers = JsoncEdit.parseObject(after).getJSONObject("provider")
        assertFalse("removed", providers.has("9router"))
        assertTrue("other kept", providers.has("local"))
        assertTrue("comment kept", after.contains("// keep me"))
    }

    @Test
    fun removingAnUnknownIdChangesNothing() {
        val text = "{\n  \"provider\": { \"local\": { \"npm\": \"x\" } }\n}\n"
        assertEquals(text, CustomProviderConfig.withoutProvider(text, "missing"))
    }
}
