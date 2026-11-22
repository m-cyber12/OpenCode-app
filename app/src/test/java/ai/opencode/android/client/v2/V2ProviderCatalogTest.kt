package ai.opencode.android.client.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Providers card lists the connectable catalog (`GET /api/integration`), not only
 * the active providers (`GET /api/provider`). A provider the user has not added a key
 * for must still be listed, with no models and not counted as connected.
 */
class V2ProviderCatalogTest {

    private fun arr(vararg objs: String) = JSONArray(objs.map { JSONObject(it) })

    @Test
    fun anUnconnectedIntegrationIsListedWithoutModelsOrConnection() {
        val providers = arr("""{"id":"openrouter","name":"OpenRouter"}""")
        val models = arr(
            """{"providerID":"openrouter","id":"m1","name":"Model 1","enabled":true,"status":"active","variants":[]}""",
        )
        val integrations = arr(
            """{"id":"openrouter","name":"OpenRouter"}""",
            """{"id":"anthropic","name":"Anthropic"}""",
        )
        val doc = V2Mapping.providerDocument(providers, models, null, integrations)

        val all = doc.getJSONArray("all")
        val ids = (0 until all.length()).map { all.getJSONObject(it).getString("id") }
        assertEquals(listOf("openrouter", "anthropic"), ids)

        val anthropic = (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("id") == "anthropic" }
        assertEquals("Anthropic", anthropic.getString("name"))
        assertEquals(0, anthropic.getJSONObject("models").length())

        // Only the provider with an enabled model is connected.
        val connected = doc.getJSONArray("connected")
        assertEquals(listOf("openrouter"), (0 until connected.length()).map { connected.getString(it) })
    }

    @Test
    fun aProviderNamedTwiceIsListedOnce() {
        val providers = arr("""{"id":"openrouter","name":"OpenRouter"}""")
        val models = arr(
            """{"providerID":"openrouter","id":"m1","name":"Model 1","enabled":true,"status":"active","variants":[]}""",
        )
        val integrations = arr("""{"id":"openrouter","name":"OpenRouter"}""")
        val all = V2Mapping.providerDocument(providers, models, null, integrations).getJSONArray("all")
        assertEquals(1, all.length())
        assertTrue(all.getJSONObject(0).getJSONObject("models").has("m1"))
    }

    @Test
    fun withoutIntegrationsTheDocumentIsUnchanged() {
        val providers = arr("""{"id":"opencode","name":"OpenCode"}""")
        val models = arr(
            """{"providerID":"opencode","id":"big-pickle","name":"Big Pickle","enabled":true,"status":"active","variants":[]}""",
        )
        val doc = V2Mapping.providerDocument(providers, models, null)
        assertEquals(1, doc.getJSONArray("all").length())
        assertEquals(1, doc.getJSONArray("connected").length())
    }

    /**
     * 2026-11-22 (owner): Cloudflare Workers AI is "Not Ready" because its key method also asks for
     * the account id. The key method's form has to reach the provider, or the dialog never asks.
     */
    @Test
    fun aKeyMethodsFormFieldsReachTheProviderEntry() {
        val providers = arr()
        val models = arr()
        val integrations = arr(
            """{"id":"cloudflare-workers-ai","name":"Cloudflare Workers AI","methods":[
                {"type":"env","names":["CLOUDFLARE_API_KEY"]},
                {"type":"key","label":"API key","form":[
                  {"type":"string","key":"accountId","title":"Enter your Cloudflare Account ID","placeholder":"e.g. 1234","required":true},
                  {"type":"boolean","key":"x","title":"not shown"}
                ]}]}""",
        )
        val all = V2Mapping.providerDocument(providers, models, null, integrations).getJSONArray("all")
        val entry = all.getJSONObject(0)
        assertEquals("cloudflare-workers-ai", entry.getString("id"))
        // The raw form passes through; the text-only filter is applied where the app parses it.
        val fields = entry.getJSONArray("keyFields")
        assertEquals(2, fields.length())
        assertEquals("accountId", fields.getJSONObject(0).getString("key"))
        assertTrue(fields.getJSONObject(0).getBoolean("required"))
    }
}
