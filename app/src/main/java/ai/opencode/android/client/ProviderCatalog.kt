package ai.opencode.android.client

import org.json.JSONObject

/**
 * Phase 10 continuation v4, item 2: the provider catalog is hundreds of entries
 * (the server reports whatever models.dev lists plus whatever the user configured),
 * so "scroll until you find OpenRouter" is not a flow.
 *
 * Two pure objects, deliberately separated from the UI so both halves are unit
 * tested without a device:
 *
 *  * [ProviderCatalog.search] - the filter behind the search box;
 *  * [CustomProviderConfig] - the config document for a provider that is NOT in
 *    the catalog (the user's own endpoint, e.g. "9router").
 *
 * Nothing here talks to the server or to the Keystore; the repository does that.
 */
object ProviderCatalog {

    /**
     * Ranked search over the catalog.
     *
     * Ranking, in order: exact id, id prefix, name prefix, then any substring of
     * either. Ordering inside a rank is by display name so the list does not
     * reshuffle between keystrokes. An empty query returns the catalog as-is
     * (the caller decides whether to render it - the search screen shows a prompt
     * instead of 300 rows, but the function does not guess).
     *
     * [limit] bounds the rendered list; a search for "a" must not compose 300 rows.
     */
    fun search(
        entries: List<OpenCodeApi.ProviderEntry>,
        query: String,
        limit: Int = 60,
    ): List<OpenCodeApi.ProviderEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries.take(limit)
        val scored = entries.mapNotNull { entry ->
            val id = entry.id.lowercase()
            val name = entry.name.lowercase()
            val rank = when {
                id == q -> 0
                id.startsWith(q) -> 1
                name.startsWith(q) -> 2
                id.contains(q) -> 3
                name.contains(q) -> 4
                else -> return@mapNotNull null
            }
            rank to entry
        }
        return scored
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }, { it.second.id }))
            .map { it.second }
            .take(limit)
    }
}

/**
 * A provider the user brings themselves: an OpenAI-compatible endpoint that the
 * server's catalog does not know.
 *
 * Why a config patch and not an app-side store: OpenCode's own mechanism for a
 * custom provider is the `provider.<id>` block in its config document
 * (`npm` = the SDK package, `options.baseURL`, `models`), plus the API key in its
 * auth store. Writing that through the server (`PATCH /global/config` + `PUT
 * /auth/:id`) means the running agent sees exactly what a hand-written
 * `opencode.json` would produce, with nothing invented on the Android side.
 *
 * The `models` map is required by upstream for a provider it has no catalog entry
 * for: without at least one model id there is nothing to select. The user types
 * ids they know their endpoint serves; the app does not guess them.
 *
 * NOT TESTED on device in this batch (see the report): the SDK package named by
 * `npm` is loaded by upstream at runtime, which on this platform depends on
 * upstream's package install path - the same path the plugin seed exists to avoid.
 * A custom provider therefore reaches "config written, key stored, provider listed
 * with its models" here, and a real turn against such an endpoint is for the
 * device pass to attempt.
 */
object CustomProviderConfig {

    /** The SDK upstream uses for any OpenAI-compatible endpoint. */
    const val NPM = "@ai-sdk/openai-compatible"

    /**
     * Provider ids become config keys and appear in every prompt payload, so they
     * are restricted to what a slug can safely be. Rejecting is better than
     * silently rewriting: the user typed the id their endpoint is known by.
     */
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    fun isUsableId(id: String): Boolean = ID_PATTERN.matches(id.trim())

    /** Split a free-text model list ("a, b c\nd") into ids, order preserved. */
    fun parseModels(raw: String): List<String> =
        raw.split(',', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * The `PATCH /global/config` body that registers the provider.
     *
     * Returns null when the input cannot produce a usable provider (bad id, no
     * models, blank base URL) - the caller shows the reason instead of writing a
     * config entry that would make the agent fail later, far away from the field
     * that caused it.
     */
    fun patchFor(
        providerID: String,
        displayName: String,
        baseUrl: String,
        models: List<String>,
    ): JSONObject? {
        val id = providerID.trim()
        val url = baseUrl.trim()
        if (!isUsableId(id)) return null
        if (url.isEmpty() || !url.startsWith("http")) return null
        if (models.isEmpty()) return null
        val modelsJson = JSONObject()
        for (m in models) {
            modelsJson.put(m, JSONObject().put("name", m))
        }
        val entry = JSONObject()
            .put("npm", NPM)
            .put("name", displayName.trim().ifEmpty { id })
            .put("options", JSONObject().put("baseURL", url))
            .put("models", modelsJson)
        return JSONObject().put("provider", JSONObject().put(id, entry))
    }
}
