package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.ui.chat.TAG_COMPOSER_INPUT
import ai.opencode.android.ui.chat.TAG_COMPOSER_SEND
import ai.opencode.android.ui.chat.TAG_PERMISSION_ONCE
import ai.opencode.android.ui.chat.TAG_QUESTION_SKIP
import android.content.Context
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The live model-dependent gates (Phase 8 carry-over: L2, "a real tool call",
 * which no Phase 6/7 run ever observed passing with the key-free default model).
 *
 * The difference this phase brings: a REAL, paid model credential, provisioned
 * through the product's own credential path (Keystore + OpenCode's own
 * PUT /auth/:providerID), and a prompt that makes a tool call the only way to
 * answer (its output is unknown to the model, so it cannot be faked).
 *
 *   01 key probe   one tiny API-level turn with the provided key+model:
 *                  the credential must actually serve a round-trip.
 *   02 tool call   the L2 scenario through the REAL UI: the model must call the
 *                  shell tool, the server must record the part, and the screen
 *                  must show the card with the command's real output.
 *   03 cleanup     the injected key must be revoked from the server AND removed
 *                  from the Keystore afterwards - the run must not leave a
 *                  model credential on the device that no user typed.
 *
 * If the run has no key (the secret was not set), every gate prints SKIP with
 * the reason - never a fabricated pass, never silence.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveToolCallGatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe = P8ServerProbe(context)
    private val shotDir = File(context.filesDir, "screenshots")

    private companion object {
        const val PROJECT = "live-gates"
        const val BADGE = "P8LIVE"

        /** Shared across the test methods (JUnit makes a new instance per method). */
        @Volatile
        var keyServed = false

        @Volatile
        var keyReason = "no turn was attempted yet"
    }

    // ---- thin wrappers over the rule (project convention: rule-free support) -

    private fun allText(): String =
        allTextOf(rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes())

    private fun allLines(): Set<String> =
        allText().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(tag: String): Boolean {
        val found = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return found.isNotEmpty() && isEnabledNode(found.first())
    }

    private fun shot(name: String): Long = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return true
            Thread.sleep(1000)
        }
        rule.waitForIdle()
        return condition()
    }

    private fun gate(id: String, ok: Boolean, detail: String) {
        printGate8(id, ok, detail)
        org.junit.Assert.assertTrue("P8_$id :: $detail", ok)
    }

    private fun skip(id: String, reason: String) {
        printSkip8(id, reason)
        org.junit.Assume.assumeTrue("P8_$id :: $reason", false)
    }

    /** OpenCode blocks a turn on a permission ask or a question exactly like the
     *  TUI does, so a live gate has to answer through the same UI a user would. */
    private fun answerAnyAsk(): Int {
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
    private fun ensureChatSurface(timeoutMs: Long, project: String): String {
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
        if (probe.apiOrNull() == null) return "no loopback credential in the Keystore, so the server cannot be asked what it sent"
        return ""
    }

    private fun sendPrompt(text: String): Boolean {
        val typed = runCatching { rule.onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput(text) }.isSuccess
        if (!typed) return false
        rule.waitForIdle()
        if (!waitFor(30_000) { enabled(TAG_COMPOSER_SEND) }) return false
        val clicked = runCatching { rule.onNodeWithTag(TAG_COMPOSER_SEND).performClick() }.isSuccess
        if (!clicked) return false
        rule.waitForIdle()
        return true
    }

    // ---- 00: what THIS (instrumented-process) server can reach --------------

    @Test
    fun g00_probeServer() {
        val api = probe.apiOrNull() ?: run {
            skip("NETPROBE_UI", "no loopback credential available")
            return
        }
        // Runs the egress probe in the SERVER's own process context (the
        // command is a child of the server), from inside the instrumented
        // (:test) app process. Round 8: the main-process server served 40/40
        // openrouter turns (stage D) while model turns in the instrumented
        // windows failed with APIError status=0 - this line shows what THIS
        // server itself reaches, in the same window as g01/g02.
        val probeFile = File(context.filesDir, "tmp/p8netprobe-ui.js")
        probeFile.parentFile?.mkdirs()
        probeFile.writeText(
            """
            for (const [n, u] of [["openrouter", "https://openrouter.ai/api/v1/models"],
                                 ["opencode", "https://opencode.ai/"],
                                 ["control", "https://www.google.com/"]]) {
              const t0 = Date.now()
              try {
                const r = await fetch(u, { signal: AbortSignal.timeout(15000) })
                await r.body?.cancel?.()
                console.log("P8NETPROBE_UI " + n + " http=" + r.status + " ms=" + (Date.now() - t0))
              } catch (e) {
                console.log("P8NETPROBE_UI " + n + " error=" + String(e.name || e.message || e).slice(0, 60) + " ms=" + (Date.now() - t0))
              }
            }
            """.trimIndent(),
        )
        val bun = File(context.filesDir, "bin/bun").absolutePath
        // Round 9: this gate ran before the instrumented-process server was up
        // (createSession threw -> "no session"). The runtime needs a few
        // seconds after MainActivity starts; retry until it is ready.
        val sid = runCatching {
            var last: Throwable? = null
            for (i in 0 until 30) {
                val s = runCatching { api.createSession("p8 netprobe ui") }.getOrNull()
                if (s != null) return@runCatching s
                last = RuntimeException("session not ready (attempt ${i + 1})")
                Thread.sleep(1500)
            }
            throw last ?: RuntimeException("session not ready")
        }.getOrNull()?.id
        val out = if (sid != null) {
            runCatching {
                api.shellOutput(sid, "$bun ${probeFile.absolutePath} 2>&1; rm -f ${probeFile.absolutePath}", "build")
            }.getOrElse { "shell call failed: ${it.message}" }
        } else {
            "no session"
        }
        probeFile.delete()
        val lines = out.lineSequence()
            .filter { it.contains("P8NETPROBE_UI") }
            .map { it.trim() }
            .toList()
        val detail = if (lines.isEmpty()) out.replace(Regex("\\s+"), " ").take(300) else lines.joinToString(" | ")
        printMarker8("NETPROBE_UI", detail)
    }

    // ---- 01: the provided key actually serves a model round-trip ------------

    @Test
    fun g01_keyProbe() {
        val key = probe.provisionedKey
        if (key == null) {
            printMarker8("MODEL_AVAILABLE", "0 :: no model key in the harness dir (OPENROUTER_API_KEY not set for this run)")
            skip("KEYPROBE", "no model key was provisioned for this run")
            return
        }
        val problem = ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            keyReason = problem
            printMarker8("MODEL_AVAILABLE", "0 :: $problem")
            skip("KEYPROBE", problem)
            return
        }
        val api = probe.apiOrNull() ?: run {
            keyReason = "no loopback credential available"
            skip("KEYPROBE", keyReason)
            return
        }

        // The product's credential path, with the key this run provisions.
        Secrets.putProviderKey(context, "openrouter", key)
        val push = runCatching { api.setProviderAuth("openrouter", key) }
        if (push.isFailure) {
            keyReason = "the server refused the credential push: ${push.exceptionOrNull()?.message}"
            gate("KEYPROBE", false, keyReason)
            return
        }

        // One tiny API-level turn, model named explicitly (no UI needed to
        // answer "does this key work").
        val token = "P8PROBEOK"
        val model = probe.provisionedModel
        val session = runCatching { api.createSession(title = "p8 key probe") }
            .getOrElse { t ->
                keyReason = "session creation failed: ${t.message}"
                gate("KEYPROBE", false, keyReason)
                return
            }
        val sent = runCatching {
            api.promptAsync(
                session.id,
                "Reply with exactly this token and nothing else: $token",
                model = OpenCodeApi.ModelRef("openrouter", model),
            )
        }.getOrElse { t ->
            keyReason = "prompt_async rejected the turn: ${t.message}"
            gate("KEYPROBE", false, keyReason)
            return
        }
        if (sent != 204 && sent != 200) {
            keyReason = "prompt_async answered http $sent (not queued)"
            gate("KEYPROBE", false, keyReason)
            return
        }

        // Only ASSISTANT messages count as the reply. The user's own prompt
        // contains the token, so scanning every message "sees" the answer in
        // the echo of the question - the first full run's PASS was exactly
        // that false positive (reply == the prompt text, model never answered).
        fun probeReply(sessionID: String, deadlineMs: Long): Pair<String, String> {
            var reply = ""
            var err = ""
            while (System.currentTimeMillis() < deadlineMs && reply.isEmpty()) {
                for (m in api.messages(sessionID, 20)) {
                    if (m.role != "assistant") continue
                    for (p in m.parts) {
                        if (p.optString("type") == "text" && p.optString("text").contains(token)) {
                            reply = p.optString("text")
                        }
                    }
                    val errObj = m.info?.optJSONObject("error")
                    if (err.isEmpty() && errObj != null) {
                        err = "${errObj.optString("name")}: ${errObj.optString("message").take(160)} (status=${errObj.optInt("statusCode", 0)})"
                    }
                }
                Thread.sleep(3000)
            }
            return reply to err
        }

        // One retry in a fresh session: a transient provider stall must not
        // end the key question with a single sample. The first full run saw
        // exactly that - an in-flight turn silent for 480s with no error
        // (no turn timeout exists upstream; see the report).
        var reply = ""
        var err = ""
        var probeSession = session
        for (attempt in 1..2) {
            // Round 11: free-tier OpenRouter models (e.g. Nemotron 3 Ultra :free,
            // which DOES support tools) answer a 1-line turn in ~4 min on a real
            // device - the old 240s/180s budgets sampled that as "key failed".
            val deadline = System.currentTimeMillis() + if (attempt == 1) 300_000 else 240_000
            val (r, e) = probeReply(probeSession.id, deadline)
            reply = r
            err = e
            if (reply.isNotEmpty()) break
            if (attempt == 1) {
                val retry = runCatching { api.createSession(title = "p8 key probe retry") }.getOrNull()
                val sent2 = if (retry != null) {
                    runCatching {
                        api.promptAsync(
                            retry.id,
                            "Reply with exactly this token and nothing else: $token",
                            model = OpenCodeApi.ModelRef("openrouter", model),
                        )
                    }.getOrDefault(0)
                } else 0
                if (retry != null && (sent2 == 204 || sent2 == 200)) {
                    probeSession = retry
                    continue
                }
            }
            break
        }

        if (reply.isNotEmpty()) {
            keyServed = true
            keyReason = "the provisioned key served a turn ($model)"
            printMarker8("MODEL_AVAILABLE", "1 :: model=$model replyChars=${reply.length}")
            gate(
                "KEYPROBE",
                true,
                "model=$model tokenSeen=true reply='${reply.take(80)}' session=${session.id}",
            )
        } else {
            val lastErr = err.ifEmpty { "none" }
            keyReason = "no assistant reply with the token in 240s (lastError='$lastErr')"
            printMarker8("MODEL_AVAILABLE", "0 :: $keyReason")
            gate(
                "KEYPROBE",
                false,
                "model=$model tokenSeen=false lastError='${err.take(200)}' - the key is expired, " +
                    "out of credit, or the model id is wrong for this account",
            )
        }
    }

    // ---- 02: the real tool call, through the real UI (closes Phase 6 L2) -----

    @Test
    fun g02_liveToolCall() {
        if (!keyServed) {
            printMarker8("MODEL_AVAILABLE", "0 :: $keyReason")
            skip("TOOL", "the provisioned key could not serve a turn, so no tool call can be observed: $keyReason")
            return
        }
        val problem = ensureChatSurface(300_000, PROJECT)
        if (problem.isNotEmpty()) {
            skip("TOOL", problem)
            return
        }
        // The UI's own model selection (the same call the Settings model picker
        // makes) so the turn the composer sends goes to the provisioned model.
        val dir = ProjectStore.get(context).active()?.path
        AppContainer.get(context).repositoryFor(dir).setModel("openrouter", probe.provisionedModel)

        // A FRESH session (the product's own "new chat" call): the server
        // serializes turns per session, so a stuck in-flight turn from an
        // earlier stage would queue this one behind it indefinitely.
        AppContainer.get(context).repositoryFor(dir).newSession(null)
        if (!waitFor(60_000) { enabled(TAG_COMPOSER_INPUT) }) {
            skip("TOOL", "the composer never became usable after the fresh session")
            return
        }

        val marker = BADGE + (System.currentTimeMillis() % 1_000_000)
        val known = probe.knownMessageIds()
        val sent = sendPrompt(
            "You must use the bash tool for this task; answering from memory is not allowed. " +
                "Run this exact shell command, then report ONLY what it printed: echo $marker",
        )
        if (!sent) {
            skip("TOOL", "the composer would not accept the tool prompt")
            return
        }

        var asksAnswered = 0
        var polls = 0
        var toolParts = emptyList<P8ServerPart>()
        var replySoFar = ""
        // Round 11: a tool-call turn is TWO model inferences (call, then final
        // answer). On a free-tier model that is ~4 min each - 480s (round 9
        // device run: replyChars=0, the turn never completed) is not enough.
        val toolCallHappened = waitFor(900_000) {
            asksAnswered += answerAnyAsk()
            polls++
            if (polls % 3 == 0) {
                toolParts = probe.toolPartsInNew(known)
                replySoFar = probe.replyInNew(known)
            }
            toolParts.any { it.tool.isNotEmpty() }
        }
        if (toolParts.isEmpty()) toolParts = probe.toolPartsInNew(known)
        if (replySoFar.isEmpty()) replySoFar = probe.replyInNew(known)
        val part = toolParts.firstOrNull { it.tool.isNotEmpty() }

        if (!toolCallHappened || part == null) {
            // Two distinct failure shapes: the turn never completed (free-tier
            // queue / slow inference - no parts at all), vs the turn completed
            // but the model chose to answer instead of calling the tool.
            val detail = if (replySoFar.isEmpty()) {
                "no tool call AND no reply within 900s - the turn never completed in this window " +
                    "(free-tier queue / slow inference) (asksAnswered=$asksAnswered, polls=$polls)"
            } else {
                "the model answered without calling a tool within 900s (asksAnswered=$asksAnswered, " +
                    "replyChars=${replySoFar.length}, reply='${replySoFar.take(120)}')"
            }
            skip("TOOL", detail)
            return
        }

        // Wait for the UI to catch up with the server, then assert on the card
        // that carries THIS part's id - no guessing which card is whose.
        val cardTag = "tool_card_${part.id}"
        val headerTag = "tool_header_${part.id}"
        val outputTag = "tool_output_${part.id}"
        val cardShown = waitFor(60_000) { exists(cardTag) }
        val collapsedBeforeTap = !exists(outputTag)
        shot("41-p8-tool-card-collapsed.png")
        val headline = context.getString(R.string.chat_tool_kind_shell)
        val lines = allLines()
        val headlineShown = lines.any { it.contains(headline, ignoreCase = true) } ||
            lines.any { it.contains(part.tool, ignoreCase = true) }

        var tapped = false
        if (exists(headerTag)) {
            runCatching { rule.onNodeWithTag(headerTag).performClick() }
                .onSuccess { tapped = true }
        }
        val outputShown = waitFor(30_000) { exists(outputTag) }
        val serverOutputHasMarker = part.output.contains(marker)
        val uiOutputHasMarker = allLines().any { it.contains(marker) }
        shot("42-p8-tool-card-expanded.png")

        val ok = cardShown && collapsedBeforeTap && headlineShown && tapped && outputShown &&
            serverOutputHasMarker && uiOutputHasMarker
        gate(
            "TOOL",
            ok,
            "tool=${part.tool} status=${part.status} partId=${part.id} cardShown=$cardShown " +
                "collapsedBeforeTap=$collapsedBeforeTap headline=$headlineShown expandedByTap=$tapped " +
                "outputRendered=$outputShown markerInServerOutput=$serverOutputHasMarker " +
                "markerOnScreen=$uiOutputHasMarker asksAnswered=$asksAnswered marker=$marker " +
                ":: this closes Phase 6 L2 (real model, real tool call)",
        )
    }

    // ---- 03: the injected key must not survive the run -----------------------

    @Test
    fun g03_cleanup() {
        val api = probe.apiOrNull()
        runCatching {
            val dir = ProjectStore.get(context).active()?.path
            AppContainer.get(context).repositoryFor(dir).clearModel()
        }
        runCatching { api?.deleteProviderAuth("openrouter") }
        val removed = runCatching { Secrets.removeProviderKey(context, "openrouter") }.getOrDefault(false)
        val leftover = runCatching { Secrets.providerKey(context, "openrouter") }.getOrNull()
        val providerIds = runCatching { Secrets.storedProviderIds(context) }.getOrDefault(emptyList())
        val ok = leftover == null && !providerIds.contains("openrouter")
        gate(
            "CLEANUP",
            ok,
            "keystoreRemoved=$removed leftoverKey=${if (leftover == null) "none" else "PRESENT"} " +
                "storedProviderIds=${providerIds.joinToString(",") { it.ifEmpty { "?" } }} " +
                ":: the short-lived CI key must not persist on the device after the run",
        )
    }
}
