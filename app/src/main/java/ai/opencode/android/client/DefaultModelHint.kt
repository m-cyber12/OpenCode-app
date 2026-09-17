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
}
