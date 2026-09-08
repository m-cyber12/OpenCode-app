package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "no provider configured" state is first-class in Phase 7. This pins the
 * pure classifier so the four states cannot drift: it derives everything from
 * two lists (OpenCode's `connected`, the Keystore's stored ids) plus whether the
 * provider list has been fetched at all.
 */
class ProviderSetupTest {

    @Test
    fun connectedWinsOverEverything() {
        assertEquals(
            ProviderSetup.CONNECTED,
            ProviderSetupClassifier.classify(connected = listOf("anthropic"), storedIds = emptyList(), providersKnown = true),
        )
        assertEquals(
            ProviderSetup.CONNECTED,
            ProviderSetupClassifier.classify(connected = listOf("anthropic"), storedIds = listOf("anthropic"), providersKnown = true),
        )
    }

    @Test
    fun aStoredKeyWithoutConnectionIsConfiguredNotConnected() {
        assertEquals(
            ProviderSetup.PROVIDER_CONFIGURED,
            ProviderSetupClassifier.classify(connected = emptyList(), storedIds = listOf("anthropic"), providersKnown = true),
        )
    }

    @Test
    fun nothingStoredAndNothingConnectedIsTheNamedEmptyState() {
        assertEquals(
            ProviderSetup.NO_PROVIDER_CONFIGURED,
            ProviderSetupClassifier.classify(connected = emptyList(), storedIds = emptyList(), providersKnown = true),
        )
    }

    @Test
    fun anUnknownProviderListIsUnknownEvenWithStoredKeys() {
        assertEquals(
            ProviderSetup.UNKNOWN,
            ProviderSetupClassifier.classify(connected = emptyList(), storedIds = listOf("anthropic"), providersKnown = false),
        )
    }
}
