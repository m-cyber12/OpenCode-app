package ai.opencode.android.ui

import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretStore
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.io.File

/**
 * Shared plumbing for the two Phase 8 gate classes (StressRecoveryGatesTest and
 * LiveToolCallGatesTest), following the Phase 6/7 convention exactly: the
 * support is deliberately FREE of any Compose test-rule type (the rule
 * implementation differs between rule flavors, so each gate class keeps its
 * own thin wrappers around `rule.onAllNodes(...)`), while everything that is
 * not UI - the verdict channel, the server-side parsing, the credential
 * plumbing - lives here ONCE.
 *
 * Verdict discipline is Phase 5's: "P8_<id> PASS|FAIL|SKIP ::" to logcat AND
 * an on-device file, because the logcat ring buffer does not survive a live
 * runtime and the file is what the host script reads deterministically.
 *
 * The model key (when the run provisions one) is NOT read from an intent or
 * the process environment: the host gate script writes it into the app's own
 * files/harness/ directory (the test-only harness area Phase 5 already uses
 * for the loopback password), and [P8ServerProbe] reads it back. The key then
 * flows through the product's own credential path - the Keystore-backed
 * SecretStore and OpenCode's own PUT /auth/:providerID - exactly like a key
 * typed into the Settings screen.
 */

internal const val P8_GATE_TAG = "OpenCode/gate"
internal const val P8_VERDICT_FILE = "p8-verdicts.txt"

private val p8VerdictSinks: List<File> by lazy {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    listOfNotNull(
        ctx.getExternalFilesDir(null)?.let { File(it, P8_VERDICT_FILE) },
        File(ctx.filesDir, P8_VERDICT_FILE),
    )
}

private fun p8Emit(line: String) {
    Log.i(P8_GATE_TAG, line)
    println(line)
    p8VerdictSinks.forEach { sink -> runCatching { sink.appendText(line + "\n") } }
}

internal fun printGate8(id: String, ok: Boolean, detail: String) {
    p8Emit("P8_$id ${if (ok) "PASS" else "FAIL"} :: $detail")
}

internal fun printSkip8(id: String, reason: String) {
    p8Emit("P8_$id SKIP :: $reason")
}

internal fun printMarker8(name: String, value: String) {
    p8Emit("P8_$name $value")
}

/** One part as the server reports it, reduced to what the P8 gates need. */
internal data class P8ServerPart(
    val id: String,
    val messageID: String,
    val role: String,
    val type: String,
    val tool: String,
    val status: String,
    val text: String,
    val output: String,
    val errorName: String,
    val errorMessage: String,
    val errorStatus: Int,
    val errorRetryable: Boolean,
    val createdMs: Long,
)

/**
 * The server side of the P8 gates: the app's own loopback client as the
 * authority on what the server sent, plus the harness-provisioned model
 * key/model. No UI types anywhere in this class.
 */
internal class P8ServerProbe(private val context: Context) {

    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

    /** The model the host provisions for this run (files/harness/model), with a
     *  cheap, tool-capable OpenRouter default. */
    val provisionedModel: String = runCatching {
        File(context.filesDir, "harness/model").readText().trim()
    }.getOrDefault("").ifEmpty {
        if (provisionedProvider == "google") "gemini-2.5-flash" else "openai/gpt-4o-mini"
    }

    /** The provider the host provisions for this run (files/harness/provider).
     *  Defaults to "openrouter" so every earlier round keeps its meaning; the
     *  device suite writes "google" when the run uses a Gemini API key (that
     *  free tier is request-limited, not credit-limited, so it can actually
     *  serve the two inferences a tool-call turn needs). */
    val provisionedProvider: String = runCatching {
        File(context.filesDir, "harness/provider").readText().trim()
    }.getOrDefault("").ifEmpty { "openrouter" }

    /** The provider key the host wrote into the harness dir, or null. */
    val provisionedKey: String? = runCatching {
        File(context.filesDir, "harness/model-key").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The app's loopback credential from the Keystore (the product path). */
    fun serverPassword(): String? = runCatching {
        SecretStore.get(context).get(Secrets.SERVER_PASSWORD)
    }.getOrNull()

    fun apiOrNull(): OpenCodeApi? = runCatching {
        val password = serverPassword() ?: return@runCatching null
        val dir = ProjectStore.get(context).active()?.path
        OpenCodeApi(base, RuntimeEnv.SERVER_USER, password, directory = dir)
    }.getOrNull()

    /** Every part (and each assistant message's own error) in the project's
     *  most recent session that has a user turn. */
    fun serverParts(): List<P8ServerPart> {
        val api = apiOrNull() ?: return emptyList()
        return runCatching {
            val sessions = api.listSessions(limit = 20).sortedByDescending { it.updatedAt }
            for (session in sessions) {
                val messages = api.messages(session.id, limit = 60)
                if (messages.none { it.role == "user" }) continue
                return@runCatching messages.flatMap { m ->
                    val created = m.info?.optJSONObject("time")?.optLong("created") ?: 0L
                    val err = m.info?.optJSONObject("error")
                    val errStatus = err?.optInt("statusCode", 0) ?: 0
                    if (m.parts.isEmpty()) {
                        // A turn that failed at the provider level can leave an
                        // assistant message with NO parts and the error only on
                        // the message info (observed: APIError status=0 turns).
                        // The parts-only view below would drop it, so surface it
                        // as one synthetic part carrying the message error.
                        if (err != null) {
                            listOf(
                                P8ServerPart(
                                    id = "info",
                                    messageID = m.id,
                                    role = m.role,
                                    type = "text",
                                    tool = "",
                                    status = "",
                                    text = "",
                                    output = "",
                                    errorName = err.optString("name"),
                                    errorMessage = err.optString("message"),
                                    errorStatus = errStatus,
                                    errorRetryable = err.optBoolean("isRetryable", false),
                                    createdMs = created,
                                ),
                            )
                        } else emptyList()
                    } else {
                        m.parts.map { p ->
                        val state = p.optJSONObject("state")
                        val metadata = state?.optJSONObject("metadata")
                        P8ServerPart(
                            id = p.optString("id"),
                            messageID = m.id,
                            role = m.role,
                            type = p.optString("type"),
                            tool = p.optString("tool"),
                            status = state?.optString("status") ?: "",
                            text = p.optString("text"),
                            output = state?.opt("output")?.toString() ?: metadata?.optString("output") ?: "",
                            errorName = err?.optString("name") ?: "",
                            errorMessage = err?.optString("message") ?: "",
                            errorStatus = errStatus,
                            errorRetryable = err?.optBoolean("isRetryable", false) ?: false,
                            createdMs = created,
                        )
                        }
                    }
                }
            }
            emptyList()
        }.getOrDefault(emptyList())
    }

    /** Message ids the server already had, captured before a prompt is sent. */
    fun knownMessageIds(): Set<String> = serverParts().map { it.messageID }.toSet()

    /** The newest assistant text in a message that did not exist before the prompt. */
    fun replyInNew(known: Set<String>): String = serverParts()
        .filter {
            it.role == "assistant" && it.type == "text" && it.text.isNotBlank() && it.messageID !in known
        }
        .maxByOrNull { it.createdMs }
        ?.text
        ?.trim()
        ?: ""

    fun toolPartsInNew(known: Set<String>): List<P8ServerPart> =
        serverParts().filter { it.type == "tool" && it.messageID !in known }

    /** The assistant error in a message that did not exist before the prompt,
     *  reduced to what the classifier needs. */
    fun errorInNew(known: Set<String>): P8ServerPart? = serverParts()
        .filter { it.messageID !in known && it.errorName.isNotEmpty() }
        .maxByOrNull { it.createdMs }
}
