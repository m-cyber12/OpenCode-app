package ai.opencode.android.client

/**
 * Owner decision (2026-11-15): the free Zen models are not offered in this release.
 *
 * Zen's shared free quota is blocking this build right now (HTTP 429,
 * `provider.free-limit`), and the fix is not ready for the release. While
 * [COMING_SOON] is true the app:
 *  - hides every Zen model from the model selector and the Models card,
 *  - shows the Zen provider in Settings with a gray "Coming soon" tag and a reason,
 *  - never picks a Zen model as the default and never sends a turn to Zen,
 *    so no request reaches the quota gate by accident.
 *
 * Set [COMING_SOON] to false to bring Zen back everywhere at once.
 */
object ZenPolicy {
    const val COMING_SOON = true

    /** True when [providerID] is the Zen provider and its models must not be offered or used. */
    fun hidden(providerID: String?): Boolean =
        COMING_SOON && providerID == OpenCodeApi.ZEN_PROVIDER_ID
}
