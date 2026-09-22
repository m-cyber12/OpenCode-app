package ai.opencode.android.client

/**
 * PHASE 9 (carried Phase 8 defect, second half): which model the composer
 * sends when the user has not pinned one.
 *
 * Upstream's `GET /provider` `default` map covers EVERY catalog provider
 * (hundreds), so "first entry" is an arbitrary provider nobody is connected to.
 * A turn naming it fails with `ProviderModelNotFoundError` (only a
 * `session.error`, no assistant message), and a turn naming nothing goes to
 * `Provider.defaultModel()` = the bundled `opencode` provider. Either way the
 * user's configured provider is not what answers.
 *
 * Rule: prefer a provider the server itself reports `connected` (its own
 * credential/env check) - [prefer] first when it is connected, then any
 * connected provider other than the bundled `opencode`, then `opencode` itself
 * - and otherwise send NO hint (server default) rather than an id that cannot
 * serve. Ids come from the server's own `default` map; nothing is invented.
 */
object DefaultModelHint {
    const val BUNDLED = "opencode"

    fun pick(p: OpenCodeApi.ProviderSnapshot, prefer: String? = null): OpenCodeApi.ModelRef? {
        val connected = p.connected.filter { it in p.defaultModel && p.defaultModel[it]!!.isNotEmpty() }
        val pick = when {
            prefer != null && prefer in connected -> prefer
            connected.any { it != BUNDLED } -> connected.first { it != BUNDLED }
            else -> connected.firstOrNull()
        } ?: return null
        return OpenCodeApi.ModelRef(pick, p.defaultModel.getValue(pick))
    }

    /**
     * PHASE 10 CONTINUATION v4 (item 3): the hint to send when the client has no
     * in-memory choice yet, i.e. on a fresh repository - which is every new chat,
     * every project switch and every app start.
     *
     * Order of preference:
     *
     *  1. **[persisted]**, the model the user actually used last - this is the fix
     *     for "a new chat defaults to the first model of the first provider". It is
     *     accepted when the server's own catalog still lists that provider, either
     *     in its `default` map or in the provider entries (a custom provider the
     *     user added by hand has models but no `default` entry). Comfortably: it is
     *     NOT required to be `connected`, because the user chose it deliberately -
     *     and the app surfaces a turn error honestly if the provider cannot serve.
     *  2. **[pick]**, the Phase 9 rule (prefer a connected provider, never a
     *     disconnected catalog entry, never an invented id), for a first run or
     *     after the stored provider left the catalog.
     *  3. no hint at all, so the server applies its own default.
     */
    fun resolve(
        persisted: OpenCodeApi.ModelRef?,
        p: OpenCodeApi.ProviderSnapshot,
        prefer: String? = null,
    ): OpenCodeApi.ModelRef? {
        if (persisted != null && knownTo(p, persisted)) return persisted
        return pick(p, prefer)
    }

    /** Does the server's own snapshot still know this provider (and this model)? */
    private fun knownTo(p: OpenCodeApi.ProviderSnapshot, ref: OpenCodeApi.ModelRef): Boolean {
        if (ref.providerID in p.defaultModel) return true
        val models = p.modelsOf(ref.providerID)
        return models.isNotEmpty() && models.any { it.id == ref.modelID }
    }
}
