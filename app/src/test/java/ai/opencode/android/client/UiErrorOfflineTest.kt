package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline and failure distinction (Phase 8 test-matrix item: "offline /
 * failure modes") plus the re-verification half of the security checklist
 * item "re-verify Phase 6": the three "not working" states - runtime
 * unavailable, runtime healthy but provider unreachable, provider
 * auth-failure - must remain machine-distinguishable, because the UI copy for
 * each is different and acting on them is different (retry vs. check network
 * vs. fix the key).
 *
 * The host-side gates (P8-PROVAUTH / P8-NOLOSTNET) drive these same branches
 * on a real device with the real server; this suite pins the classifier logic
 * on the JVM so a regression is caught before it can ever reach a device.
 */
class UiErrorOfflineTest {

    private fun turn(
        name: String,
        message: String,
        status: Int = 0,
        retryable: Boolean = false,
    ): AgentAvailability = UiError.classifyTurnError(name, message, status, retryable)

    // ---- provider auth-failure ----------------------------------------------

    @Test
    fun theUpstreamProviderAuthErrorNameAlwaysWins() {
        // Upstream sets the NAME; the message text must not be able to talk
        // it into a different bucket (honesty: the cause is what upstream said).
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            turn(UiError.NAME_PROVIDER_AUTH, "whatever the message says"),
        )
    }

    @Test
    fun apiError401IsAProviderAuthFailure() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            turn(UiError.NAME_API, "401 Unauthorized", status = 401),
        )
    }

    @Test
    fun apiError403IsAProviderAuthFailure() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            turn(UiError.NAME_API, "forbidden", status = 403),
        )
    }

    @Test
    fun authHintsInTheTextAreRecognisedWhenThereIsNoStatus() {
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            turn(UiError.NAME_UNKNOWN, "OpenRouter error: invalid api key"),
        )
    }

    @Test
    fun a429WithAuthSoundingTextIsStillUnreachable() {
        // Status beats text: the provider answered "rate limited", so that is
        // the cause even if the body also mentions credits.
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            turn(UiError.NAME_API, "credit balance, rate limit exceeded", status = 429),
        )
    }

    // ---- runtime healthy, provider unreachable --------------------------------

    @Test
    fun apiError5xxIsAProviderReachabilityFailure() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals(
                "status $status must be provider-unreachable",
                AgentAvailability.PROVIDER_UNREACHABLE,
                turn(UiError.NAME_API, "provider error", status = status),
            )
        }
    }

    @Test
    fun aRetryableApiErrorIsProviderReachability() {
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            turn(UiError.NAME_API, "retry this please", retryable = true),
        )
    }

    @Test
    fun networkTextIsRecognisedFromTheKnownHintVocabulary() {
        // These are the strings the host gate (P8-NOLOSTNET) produces by
        // killing the network and letting the real server fail the model call;
        // pinning them here keeps the two vocabularies honest.
        val hints = listOf(
            "fetch failed",
            "fetch failed: ECONNREFUSED",
            "ENOTFOUND openrouter.ai",
            "socket hang up",
            "timeout of 60000ms exceeded",
            "The operation timed out",
            "Too Many Requests from upstream",
            "upstream overloaded",
            "service unavailable",
            "bad gateway",
            "network request failed",
            "temporarily unavailable",
        )
        for (hint in hints) {
            assertEquals(
                "hint '$hint' must classify as provider-unreachable",
                AgentAvailability.PROVIDER_UNREACHABLE,
                turn(UiError.NAME_UNKNOWN, hint),
            )
        }
    }

    @Test
    fun authHintsTakePrecedenceOverNetworkHints() {
        // Deliberate: a message that mentions both gets the more actionable
        // reading. Pinned so a reorder is a conscious change, not an accident.
        assertEquals(
            AgentAvailability.PROVIDER_AUTH,
            turn(UiError.NAME_UNKNOWN, "forbidden: network said denied"),
        )
    }

    @Test
    fun aNamedProviderErrorWithNoRecognisableTextIsProviderOther() {
        // Upstream named it, so it is a provider-side failure, but neither
        // auth nor network is supported by the text: the honest bucket.
        assertEquals(
            AgentAvailability.PROVIDER_OTHER,
            turn(UiError.NAME_UNKNOWN, "the model returned nothing"),
        )
    }

    @Test
    fun anUnrecognisedNameWithNoTextIsUnknown() {
        // No name, no text: there is nothing to classify and no cause to
        // invent.
        assertEquals(AgentAvailability.UNKNOWN, turn("", ""))
        // No name, and text that supports neither an auth nor a network
        // reading: still unknown - never a made-up provider cause.
        assertEquals(AgentAvailability.UNKNOWN, turn("", "something happened"))
    }

    @Test
    fun structuralFailuresAreNotNetworkProblems() {
        for (name in listOf(
            UiError.NAME_CONTEXT_OVERFLOW,
            UiError.NAME_CONTENT_FILTER,
            UiError.NAME_OUTPUT_LENGTH,
            UiError.NAME_STRUCTURED_OUTPUT,
        )) {
            assertEquals(
                "$name must be provider-other even with network-looking text",
                AgentAvailability.PROVIDER_OTHER,
                turn(name, "timed out"),
            )
        }
    }

    @Test
    fun anAbortIsNeverAServerProblem() {
        assertEquals(AgentAvailability.ABORTED, turn(UiError.NAME_ABORTED, "aborted by user"))
    }

    // ---- the app's own call to the loopback server ----------------------------

    @Test
    fun aTransportFailureOfTheAppCallIsServerUnreachable() {
        assertEquals(
            AgentAvailability.SERVER_UNREACHABLE,
            UiError.classifyServerCall(-1, "failed to connect to /127.0.0.1 port 4111"),
        )
    }

    @Test
    fun theAppCallStatusesClassifySeparately() {
        assertEquals(AgentAvailability.SERVER_AUTH, UiError.classifyServerCall(401, "unauthorized"))
        assertEquals(AgentAvailability.SERVER_AUTH, UiError.classifyServerCall(403, "forbidden"))
        assertEquals(AgentAvailability.REQUEST_REJECTED, UiError.classifyServerCall(404, "not found"))
        assertEquals(AgentAvailability.SERVER_UNREACHABLE, UiError.classifyServerCall(500, "boom"))
    }

    // ---- runtime supervisor states ---------------------------------------------

    @Test
    fun runtimeStateNamesMapOntoTheTwoRuntimeBuckets() {
        assertEquals(AgentAvailability.READY, UiError.classifyRuntimeStatus("HEALTHY"))
        for (starting in listOf("EXTRACTING", "STARTING", "STOPPED_IDLE")) {
            assertEquals(AgentAvailability.RUNTIME_STARTING, UiError.classifyRuntimeStatus(starting))
        }
        for (down in listOf("CRASHED_RESTARTING", "FATAL", "UNSUPPORTED_DEVICE", "STOPPED")) {
            assertEquals(AgentAvailability.RUNTIME_UNAVAILABLE, UiError.classifyRuntimeStatus(down))
        }
    }

    // ---- the UI's single state: precedence --------------------------------------

    @Test
    fun theRuntimeStateAlwaysWinsOverEverything() {
        // A dead runtime must never be dressed up as a provider problem.
        assertEquals(
            AgentAvailability.RUNTIME_UNAVAILABLE,
            UiError.combine(
                AgentAvailability.RUNTIME_UNAVAILABLE,
                AgentAvailability.SERVER_UNREACHABLE,
                AgentAvailability.PROVIDER_UNREACHABLE,
            ),
        )
        // And a provider failure must never make the whole app look broken:
        // runtime healthy, server healthy, one bad turn.
        assertEquals(
            AgentAvailability.PROVIDER_UNREACHABLE,
            UiError.combine(
                AgentAvailability.READY,
                AgentAvailability.READY,
                AgentAvailability.PROVIDER_UNREACHABLE,
            ),
        )
        // Starting beats an unanswered server (we are not up yet - nothing
        // should be answering).
        assertEquals(
            AgentAvailability.RUNTIME_STARTING,
            UiError.combine(
                AgentAvailability.RUNTIME_STARTING,
                AgentAvailability.SERVER_UNREACHABLE,
                AgentAvailability.READY,
            ),
        )
    }

    @Test
    fun onlyLocalFailureStatesDisableSending() {
        // Provider problems keep the composer enabled: the app is fine and the
        // fixed key / restored network is the way out.
        for (blocking in listOf(
            AgentAvailability.RUNTIME_STARTING,
            AgentAvailability.RUNTIME_UNAVAILABLE,
            AgentAvailability.SERVER_UNREACHABLE,
            AgentAvailability.SERVER_AUTH,
        )) {
            org.junit.Assert.assertFalse("$blocking must block sending", UiError.canSend(blocking))
        }
        for (allowed in listOf(
            AgentAvailability.READY,
            AgentAvailability.REQUEST_REJECTED,
            AgentAvailability.PROVIDER_UNREACHABLE,
            AgentAvailability.PROVIDER_AUTH,
            AgentAvailability.PROVIDER_OTHER,
            AgentAvailability.ABORTED,
            AgentAvailability.UNKNOWN,
        )) {
            org.junit.Assert.assertTrue("$allowed must keep sending enabled", UiError.canSend(allowed))
        }
    }
}
