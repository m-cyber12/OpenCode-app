package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the failure classifier.
 *
 * The Phase 6 requirement is that offline and degraded states are distinguishable:
 * "the agent on this phone is down", "the agent is up but the model service is not
 * reachable" and "the model service rejected the key" must not look the same, and
 * only the first may disable the composer. The error names and status codes used
 * here are upstream's own (`AssistantError` in `packages/core/src/v1/session.ts` at
 * the pinned commit), so this is a test against a real contract rather than against
 * invented strings.
 */
class UiErrorTest {

    @Test
    fun providerAuthErrorIsProviderAuthWhateverTheMessageSays() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            UiError.classifyTurnError(UiError.NAME_PROVIDER_AUTH, "anything at all"),
        )
    }

    @Test
    fun apiErrorStatusCodesDecideBetweenAuthNetworkAndOther() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            UiError.classifyTurnError(UiError.NAME_API, "nope", statusCode = 401),
        )
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            UiError.classifyTurnError(UiError.NAME_API, "nope", statusCode = 403),
        )
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.classifyTurnError(UiError.NAME_API, "slow down", statusCode = 429),
        )
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.classifyTurnError(UiError.NAME_API, "timeout", statusCode = 408),
        )
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.classifyTurnError(UiError.NAME_API, "bad gateway", statusCode = 502),
        )
        assertEquals(
            AgentAvailability.PROVIDER_OTHER,
            UiError.classifyTurnError(UiError.NAME_API, "malformed request", statusCode = 400),
        )
    }

    @Test
    fun retryableApiErrorIsTreatedAsReachabilityNotAsABug() {
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.classifyTurnError(UiError.NAME_API, "upstream hiccup", statusCode = 0, retryable = true),
        )
    }

    @Test
    fun abortIsItsOwnStateAndIsNotAnError() {
        assertEquals(
            AgentAvailability.ABORTED,
            UiError.classifyTurnError(UiError.NAME_ABORTED, "Aborted"),
        )
    }

    @Test
    fun modelSideLimitsAreProviderFailures() {
        for (name in listOf(
            UiError.NAME_CONTEXT_OVERFLOW,
            UiError.NAME_CONTENT_FILTER,
            UiError.NAME_OUTPUT_LENGTH,
            UiError.NAME_STRUCTURED_OUTPUT,
        )) {
            assertEquals(name, AgentAvailability.PROVIDER_OTHER, UiError.classifyTurnError(name, "x"))
        }
    }

    @Test
    fun unknownNamesFallBackToMessageHints() {
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.classifyTurnError(UiError.NAME_UNKNOWN, "fetch failed: ECONNREFUSED"),
        )
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            UiError.classifyTurnError("SomethingNew", "Invalid API key provided"),
        )
        assertEquals(
            AgentAvailability.PROVIDER_OTHER,
            UiError.classifyTurnError("SomethingNew", "the model declined"),
        )
    }

    @Test
    fun anEmptyErrorIsUnknownRatherThanSomethingInvented() {
        assertEquals(AgentAvailability.UNKNOWN, UiError.classifyTurnError("", ""))
    }

    @Test
    fun serverCallClassificationSeparatesTransportFromRefusal() {
        assertEquals(AgentAvailability.SERVER_UNREACHABLE, UiError.classifyServerCall(-1, "failed to connect"))
        assertEquals(AgentAvailability.SERVER_AUTH, UiError.classifyServerCall(401, "unauthorized"))
        assertEquals(AgentAvailability.SERVER_AUTH, UiError.classifyServerCall(403, "forbidden"))
        assertEquals(AgentAvailability.REQUEST_REJECTED, UiError.classifyServerCall(404, "no such session"))
        assertEquals(AgentAvailability.SERVER_UNREACHABLE, UiError.classifyServerCall(503, "unavailable"))
        assertEquals(AgentAvailability.UNKNOWN, UiError.classifyServerCall(0, "odd"))
    }

    @Test
    fun runtimeStatesMapOntoStartingVersusUnavailable() {
        assertEquals(AgentAvailability.READY, UiError.classifyRuntimeStatus("HEALTHY"))
        assertEquals(AgentAvailability.RUNTIME_STARTING, UiError.classifyRuntimeStatus("EXTRACTING"))
        assertEquals(AgentAvailability.RUNTIME_STARTING, UiError.classifyRuntimeStatus("STARTING"))
        assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus("CRASHED_RESTARTING"))
        assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus("FATAL"))
        assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus("STOPPED"))
        assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus("UNSUPPORTED_DEVICE"))
        assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus(""))
    }

    @Test
    fun sendingIsBlockedOnlyByTheLocalAgent() {
        assertFalse(UiError.canSend(AgentAvailability.RUNTIME_STARTING))
        assertFalse(UiError.canSend(AgentAvailability.RUNTIME_UNAVAILABLE))
        assertFalse(UiError.canSend(AgentAvailability.SERVER_UNREACHABLE))
        assertFalse(UiError.canSend(AgentAvailability.SERVER_AUTH))
        // The app is fine in all of these: the composer must stay usable.
        assertTrue(UiError.canSend(AgentAvailability.PROVIDER_UNREACHABLE))
        assertTrue(UiError.canSend(AgentAvailability.PROVIDER_AUTH))
        assertTrue(UiError.canSend(AgentAvailability.PROVIDER_OTHER))
        assertTrue(UiError.canSend(AgentAvailability.ABORTED))
        assertTrue(UiError.canSend(AgentAvailability.REQUEST_REJECTED))
        assertTrue(UiError.canSend(AgentAvailability.READY))
    }

    @Test
    fun theMostLocalFailureWins() {
        assertEquals(
            AgentAvailability.RUNTIME_UNAVAILABLE,
            UiError.combine(
                AgentAvailability.RUNTIME_UNAVAILABLE,
                AgentAvailability.SERVER_UNREACHABLE,
                AgentAvailability.PROVIDER_AUTH,
            ),
        )
        assertEquals(
            AgentAvailability.RUNTIME_STARTING,
            UiError.combine(
                AgentAvailability.RUNTIME_STARTING,
                AgentAvailability.SERVER_UNREACHABLE,
                AgentAvailability.READY,
            ),
        )
        assertEquals(
            AgentAvailability.SERVER_AUTH,
            UiError.combine(AgentAvailability.READY, AgentAvailability.SERVER_AUTH, AgentAvailability.READY),
        )
    }

    @Test
    fun aProviderFailureNeverMakesTheWholeAppLookBroken() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            UiError.combine(AgentAvailability.READY, AgentAvailability.READY, AgentAvailability.PROVIDER_AUTH),
        )
        assertEquals(
            AgentAvailability.READY,
            UiError.combine(AgentAvailability.READY, AgentAvailability.UNKNOWN, AgentAvailability.ABORTED),
        )
        assertEquals(
            AgentAvailability.READY,
            UiError.combine(AgentAvailability.READY, AgentAvailability.READY, AgentAvailability.READY),
        )
    }
}
