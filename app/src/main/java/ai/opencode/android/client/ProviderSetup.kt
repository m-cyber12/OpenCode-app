package ai.opencode.android.client

/**
 * First-class "is a model actually configured" state, derived only from facts the
 * server and the Keystore already report.
 *
 * Phase 6 found the key-free default provider is not guaranteed to be connected on
 * every device, so "no provider configured yet" must read as a labelled state in
 * Settings rather than an empty field. This is the pure classifier for that state;
 * the UI only maps it onto copy.
 */
enum class ProviderSetup {
    /** No API key is stored and OpenCode reports nothing connected. */
    NO_PROVIDER_CONFIGURED,

    /** A key is stored in the Keystore but OpenCode does not report it connected. */
    PROVIDER_CONFIGURED,

    /** OpenCode reports at least one connected provider. */
    CONNECTED,

    /** The provider list has not been fetched yet (server not reachable yet). */
    UNKNOWN,
}

object ProviderSetupClassifier {

    fun classify(
        connected: List<String>,
        storedIds: List<String>,
        providersKnown: Boolean,
    ): ProviderSetup = when {
        !providersKnown -> ProviderSetup.UNKNOWN
        connected.isNotEmpty() -> ProviderSetup.CONNECTED
        storedIds.isNotEmpty() -> ProviderSetup.PROVIDER_CONFIGURED
        else -> ProviderSetup.NO_PROVIDER_CONFIGURED
    }
}
