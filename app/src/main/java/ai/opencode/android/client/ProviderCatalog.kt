package ai.opencode.android.client

import ai.opencode.android.client.v2.JsoncEdit
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
     * Why [patchFor] would refuse these inputs, in words the user can act on.
     * Null when the inputs can produce a provider. The API key is checked by the caller.
     *
     * 2026-11-21 (owner): the form used to refuse without saying which field, so the
     * tap looked like it did nothing. The message names the field.
     */
    fun problemWith(providerID: String, baseUrl: String, models: List<String>): String? {
        if (!isUsableId(providerID)) {
            return "the provider id must use lowercase letters, digits, dot, underscore or dash, for example \"9router\""
        }
        val url = baseUrl.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return "the base URL must start with http:// or https://"
        }
        if (models.isEmpty()) return "list at least one model id"
        return null
    }

    /**
     * The `provider` object after an add: every id already there is kept, and the ids in
     * [added] are set. Adding one provider must never drop another one's config.
     */
    fun mergeProviders(existing: JSONObject?, added: JSONObject): JSONObject {
        val out = JSONObject(existing?.toString() ?: "{}")
        for (id in added.keys()) out.put(id, added.get(id))
        return out
    }

    /** A custom provider as the config file holds it, read back so the user can edit or remove it. */
    data class Saved(val id: String, val name: String, val baseUrl: String, val models: List<String>)

    /**
     * The custom providers in a config document: the `provider` entries written with the
     * OpenAI-compatible SDK. A hand-written entry of the same shape is listed too, which is
     * the point: the user can manage what they added, whichever way they added it.
     */
    fun savedFrom(text: String): List<Saved> {
        val providers = JsoncEdit.parseObject(text).optJSONObject("provider") ?: return emptyList()
        return providers.keys().asSequence().mapNotNull { id ->
            val entry = providers.optJSONObject(id) ?: return@mapNotNull null
            if (entry.optString("npm") != NPM) return@mapNotNull null
            val baseUrl = entry.optJSONObject("options")?.optString("baseURL").orEmpty()
            val models = entry.optJSONObject("models")?.keys()?.asSequence()?.toList().orEmpty()
            Saved(id, entry.optString("name").ifEmpty { id }, baseUrl, models)
        }.toList()
    }

    /** [text] with the `provider.<id>` entry removed. Other providers and comments are kept. */
    fun withoutProvider(text: String, providerID: String): String {
        val providers = JsoncEdit.parseObject(text).optJSONObject("provider") ?: return text
        if (!providers.has(providerID)) return text
        providers.remove(providerID)
        return JsoncEdit.withKey(text, "provider", providers.toString(2))
    }

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
        if (problemWith(providerID, baseUrl, models) != null) return null
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
