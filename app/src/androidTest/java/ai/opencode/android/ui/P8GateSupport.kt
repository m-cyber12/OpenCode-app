package ai.opencode.android.ui

import ai.opencode.android.MainActivity
import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretStore
import ai.opencode.android.ui.chat.TAG_COMPOSER_INPUT
import ai.opencode.android.ui.chat.TAG_COMPOSER_SEND
import ai.opencode.android.ui.chat.TAG_PERMISSION_ONCE
import ai.opencode.android.ui.chat.TAG_QUESTION_SKIP
import ai.opencode.android.ui.chat.TAG_TURN_ERROR
import android.content.Context
import android.util.Log
import androidx.compose.ui.test.SemanticsNodeInteractionContainer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import java.io.File

/**
 * Shared plumbing for the two Phase 8 gate classes (StressRecoveryGatesTest and
 * LiveToolCallGatesTest).
 *
 * The Phase 6/7 gate classes each keep their own copy of these helpers because
 * their verdict prefix (P6_) is part of Phase 6's evidence contract. The Phase 8
 * classes are new, so they share ONE implementation of the same mechanics - the
 * server is the authority on what was sent, the UI is the authority on what was
 * shown - under a P8_ verdict prefix written to p8-verdicts.txt (same discipline:
 * logcat AND an on-device file, because the logcat ring buffer does not survive a
 * live runtime and the file is what the host script reads deterministically).
 *
 * The model key (when the run provisions one) is NOT read from an intent or the
 * process environment: the host gate script writes it into the app's own
 * files/harness/ directory (the test-only harness area Phase 5 already uses for
 * the loopback password), and these helpers read it back. The key then flows
 * through the product's own credential path - the Keystore-backed SecretStore
 * and OpenCode's own PUT /auth/:providerID - exactly like a key typed into the
 * Settings screen.
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

// Bound to the container interface rather than the concrete rule class: the
// compose-test-junit4 version in this project does not expose the concrete
// rule type on the androidTest compile classpath under a stable name, and the
// container is all the support code actually uses (node queries + idle waits).
internal class P8GateSupport<T : SemanticsNodeInteractionContainer>(private val rule: T) {

    private val context: Context = ApplicationProvider.getApplicationContext()
    val shotDir: File = File(context.filesDir, "screenshots")

    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

    /** The model the host provisions for this run (files/harness/model), with a
     *  cheap, tool-capable OpenRouter default. */
    val provisionedModel: String = runCatching {
        File(context.filesDir, "harness/model").readText().trim()
    }.getOrDefault("").ifEmpty { "openai/gpt-4o-mini" }

    /** The OpenRouter key the host wrote into the harness dir, or null. */
    val provisionedKey: String? = runCatching {
        File(context.filesDir, "harness/model-key").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ---- thin wrappers over the rule ---------------------------------------

    private fun nodes() = rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes()

    private fun allText() = allTextOf(nodes())

    internal fun allLines(): Set<String> =
        allText().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(tag: String): Boolean {
        val found = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return found.isNotEmpty() && isEnabledNode(found.first())
    }

    fun shot(name: String): Long = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }

    /** Is a turn-error card or the availability banner on screen right now? */
    fun existsTurnErrorOrBanner(): Boolean =
        rule.onAllNodesWithTag(TAG_TURN_ERROR).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithTag("availability_banner").fetchSemanticsNodes().isNotEmpty()

    /** Public-on-purpose: the tool-call gate polls a part-id-scoped card tag. */
    fun ruleHasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    fun ruleOnTag(tag: String) = rule.onNodeWithTag(tag)

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return true
            Thread.sleep(1000)
        }
        rule.waitForIdle()
        return condition()
    }

    fun gate(id: String, ok: Boolean, detail: String) {
        printGate8(id, ok, detail)
        org.junit.Assert.assertTrue("P8_$id :: $detail", ok)
    }

    fun skip(id: String, reason: String) {
        printSkip8(id, reason)
        org.junit.Assume.assumeTrue("P8_$id :: $reason", false)
    }

    // ---- the app's own client, as the authority on what the server sent -----

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

    /** OpenCode blocks a turn on a permission ask or a question exactly like the TUI
     *  does, so a live gate has to answer through the same UI a user would. */
    fun answerAnyAsk(): Int {
        var answered = 0
        val once = rule.onAllNodesWithTag(TAG_PERMISSION_ONCE).fetchSemanticsNodes()
        if (once.isNotEmpty() && isEnabledNode(once.first())) {
            runCatching { rule.onAllNodesWithTag(TAG_PERMISSION_ONCE)[0].performClick() }
            answered++
        }
        val skipQuestion = rule.onAllNodesWithTag(TAG_QUESTION_SKIP).fetchSemanticsNodes()
        if (skipQuestion.isNotEmpty() && isEnabledNode(skipQuestion.first())) {
            runCatching { rule.onAllNodesWithTag(TAG_QUESTION_SKIP)[0].performClick() }
            answered++
        }
        if (answered > 0) rule.waitForIdle()
        return answered
    }

    /** "" when a chat surface with a usable composer is on screen, else the reason. */
    fun ensureChatSurface(timeoutMs: Long, project: String): String {
        val leftWelcome = waitFor(timeoutMs) { exists("projects_screen") || exists("chat_screen") }
        if (!leftWelcome) return "the app never left the welcome screen within ${timeoutMs / 1000}s"
        if (exists("projects_screen")) {
            if (exists("project_row_$project")) {
                runCatching { rule.onNodeWithTag("project_row_$project").performClick() }
            } else {
                runCatching {
                    rule.onNodeWithTag("project_name_input").performTextInput(project)
                    rule.waitForIdle()
                    rule.onNodeWithTag("project_create").performClick()
                }
            }
            rule.waitForIdle()
        }
        if (!waitFor(180_000) { exists("chat_screen") }) return "no conversation screen after opening the project"
        if (!waitFor(180_000) { enabled(TAG_COMPOSER_INPUT) }) {
            return "the composer stayed disabled (agent not ready): ${allText().take(160)}"
        }
        if (apiOrNull() == null) return "no loopback credential in the Keystore, so the server cannot be asked what it sent"
        return ""
    }

    fun sendPrompt(text: String): Boolean {
        val typed = runCatching { rule.onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput(text) }.isSuccess
        if (!typed) return false
        rule.waitForIdle()
        if (!waitFor(30_000) { enabled(TAG_COMPOSER_SEND) }) return false
        val clicked = runCatching { rule.onNodeWithTag(TAG_COMPOSER_SEND).performClick() }.isSuccess
        if (!clicked) return false
        rule.waitForIdle()
        return true
    }
}
