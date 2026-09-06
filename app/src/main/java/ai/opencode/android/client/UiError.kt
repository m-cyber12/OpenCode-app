package ai.opencode.android.client

/**
 * Which kind of "not working" the user is looking at.
 *
 * Phase 6 requires the degraded states to be distinguishable: the app must not
 * look broken because a model provider is unreachable, and it must not claim a
 * provider problem when the local agent itself is down. This enum is the whole
 * vocabulary; [UiError] derives it from facts the runtime and the server already
 * report, and the UI layer maps each value onto human-readable copy.
 *
 * Nothing here is invented: the error names are upstream's own (see
 * `packages/core/src/v1/session.ts` at the pinned commit - `ProviderAuthError`,
 * `APIError`, `MessageAbortedError`, `ContextOverflowError`, `ContentFilterError`,
 * `MessageOutputLengthError`, `StructuredOutputError`, `UnknownError`), and the
 * HTTP statuses are the ones the loopback server returns.
 */
enum class AgentAvailability {
    /** Runtime healthy, server answering, no turn error. */
    READY,

    /** The embedded runtime is extracting or starting. Not an error: not ready yet. */
    RUNTIME_STARTING,

    /** The local agent is down (crashed and restarting, stopped, fatal, unsupported device). */
    RUNTIME_UNAVAILABLE,

    /** The supervisor reports healthy but the loopback server is not answering. */
    SERVER_UNREACHABLE,

    /** The server answered 401/403 to the app's own loopback credential. */
    SERVER_AUTH,

    /** The server answered 4xx to a request the app made (our request, not the provider). */
    REQUEST_REJECTED,

    /** Server fine; the model provider or the network to it is unreachable/slow/rate-limited. */
    PROVIDER_UNREACHABLE,

    /** Server fine; the provider rejected the credentials for the model. */
    PROVIDER_AUTH,

    /** Server fine; the provider answered with a non-auth, non-network failure. */
    PROVIDER_OTHER,

    /** The turn was stopped - by the user, or by upstream's abort path. */
    ABORTED,

    /** Something failed that none of the above explains. The raw text is always shown too. */
    UNKNOWN,
}

/**
 * Pure classification of failures into [AgentAvailability].
 *
 * Deliberately free of Android types so it is covered by JVM unit tests with real
 * upstream error names/statuses, and deliberately lossless: the caller keeps the
 * server's own text and shows it verbatim in an expandable detail section. A
 * human-readable headline is never a substitute for the upstream message.
 */
object UiError {

    /** Upstream `NamedError` names that appear in `assistant.error` / `session.error`. */
    const val NAME_PROVIDER_AUTH = "ProviderAuthError"
    const val NAME_API = "APIError"
    const val NAME_ABORTED = "MessageAbortedError"
    const val NAME_CONTEXT_OVERFLOW = "ContextOverflowError"
    const val NAME_CONTENT_FILTER = "ContentFilterError"
    const val NAME_OUTPUT_LENGTH = "MessageOutputLengthError"
    const val NAME_STRUCTURED_OUTPUT = "StructuredOutputError"
    const val NAME_UNKNOWN = "UnknownError"

    private val NETWORK_HINTS = listOf(
        "fetch failed", "enotfound", "econnrefused", "econnreset", "etimedout", "ehostunreach",
        "enetunreachable", "socket hang up", "network", "timeout", "timed out", "temporarily unavailable",
        "service unavailable", "bad gateway", "gateway time-out", "overloaded", "rate limit", "too many requests",
    )
    private val AUTH_HINTS = listOf(
        "invalid api key", "invalid_api_key", "incorrect api key", "unauthorized", "unauthenticated",
        "authentication", "api key not found", "no such provider", "access denied", "forbidden", "credit",
        "billing", "subscription",
    )

    /**
     * Classify an error the SERVER reported about a turn (`session.error` event or
     * an assistant message's own `error` field).
     *
     * @param name upstream `NamedError` name
     * @param message upstream message text (never rewritten by us)
     * @param statusCode `APIError.statusCode` when present, else 0
     * @param retryable `APIError.isRetryable`
     */
    fun classifyTurnError(
        name: String,
        message: String,
        statusCode: Int = 0,
        retryable: Boolean = false,
    ): AgentAvailability {
        val lower = message.lowercase()
        return when (name) {
            NAME_PROVIDER_AUTH -> AgentAvailability.PROVIDER_AUTH
            NAME_ABORTED -> AgentAvailability.ABORTED
            NAME_API -> when {
                statusCode == 401 || statusCode == 403 -> AgentAvailability.PROVIDER_AUTH
                statusCode == 408 || statusCode == 429 || statusCode in 500..599 -> AgentAvailability.PROVIDER_UNREACHABLE
                retryable -> AgentAvailability.PROVIDER_UNREACHABLE
                statusCode in 400..499 -> AgentAvailability.PROVIDER_OTHER
                hints(lower)
            }
            NAME_CONTEXT_OVERFLOW, NAME_CONTENT_FILTER, NAME_OUTPUT_LENGTH, NAME_STRUCTURED_OUTPUT ->
                AgentAvailability.PROVIDER_OTHER
            NAME_UNKNOWN -> hints(lower)
            "" -> if (message.isBlank()) AgentAvailability.UNKNOWN else hints(lower)
            else -> hints(lower)
        }
    }

    private fun hints(lower: String): AgentAvailability = when {
        AUTH_HINTS.any { lower.contains(it) } -> AgentAvailability.PROVIDER_AUTH
        NETWORK_HINTS.any { lower.contains(it) } -> AgentAvailability.PROVIDER_UNREACHABLE
        else -> AgentAvailability.PROVIDER_OTHER
    }

    /**
     * Classify a failure of the app's OWN call to the loopback server, from the
     * status code [OpenCodeApi.ApiException] carries (-1 = transport failure, i.e.
     * nothing answered).
     */
    fun classifyServerCall(status: Int, message: String): AgentAvailability {
        val lower = message.lowercase()
        return when {
            status == -1 -> AgentAvailability.SERVER_UNREACHABLE
            status == 401 || status == 403 -> AgentAvailability.SERVER_AUTH
            status in 400..499 -> AgentAvailability.REQUEST_REJECTED
            status in 500..599 -> AgentAvailability.SERVER_UNREACHABLE
            NETWORK_HINTS.any { lower.contains(it) } -> AgentAvailability.SERVER_UNREACHABLE
            else -> AgentAvailability.UNKNOWN
        }
    }

    /**
     * Map the runtime supervisor's own state name (see
     * `ai.opencode.android.runtime.RuntimeStatus`) onto availability. Takes the
     * NAME so this file stays Android-free and unit-testable.
     */
    fun classifyRuntimeStatus(statusName: String): AgentAvailability = when (statusName) {
        "HEALTHY" -> AgentAvailability.READY
        "EXTRACTING", "STARTING", "STOPPED_IDLE" -> AgentAvailability.RUNTIME_STARTING
        else -> AgentAvailability.RUNTIME_UNAVAILABLE
    }

    /**
     * Whether a turn can even be attempted. Only the local server's own absence
     * blocks sending: a provider failure must NOT disable the composer, because
     * the app is fine and the user's next attempt (or a fixed key) is the way out.
     * That asymmetry is the whole point of distinguishing the states.
     */
    fun canSend(a: AgentAvailability): Boolean = when (a) {
        AgentAvailability.RUNTIME_STARTING,
        AgentAvailability.RUNTIME_UNAVAILABLE,
        AgentAvailability.SERVER_UNREACHABLE,
        AgentAvailability.SERVER_AUTH,
        -> false
        else -> true
    }

    /**
     * The single state the UI shows, from the three facts it has. Precedence is
     * "most local first": if the runtime is down nothing else matters, and a
     * provider failure must never be allowed to make the whole app look broken.
     */
    fun combine(
        runtime: AgentAvailability,
        server: AgentAvailability,
        turn: AgentAvailability,
    ): AgentAvailability = when {
        runtime == AgentAvailability.RUNTIME_UNAVAILABLE -> runtime
        runtime == AgentAvailability.RUNTIME_STARTING && server == AgentAvailability.SERVER_UNREACHABLE ->
            AgentAvailability.RUNTIME_STARTING
        runtime != AgentAvailability.READY -> runtime
        server != AgentAvailability.READY && server != AgentAvailability.UNKNOWN -> server
        turn != AgentAvailability.READY && turn != AgentAvailability.ABORTED -> turn
        else -> AgentAvailability.READY
    }
}
