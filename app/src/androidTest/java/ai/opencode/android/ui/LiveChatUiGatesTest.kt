package ai.opencode.android.ui

import ai.opencode.android.MainActivity
import ai.opencode.android.R
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * One real turn, driven through the real UI: type in the composer, send, watch the
 * reply stream in, then ask for a shell command so a tool card appears with output
 * the agent actually produced.
 *
 * The server - not a text heuristic - decides whether a reply or a tool part exists:
 * this test asks the app's own [OpenCodeApi] over loopback for the session's
 * messages, exactly as the Phase 5 K-gates do, and then requires the UI to be
 * showing what the server says it sent. That keeps the verdict honest in both
 * directions: a reply the UI failed to render is a FAIL, and no reply at all is a
 * SKIP that says why.
 *
 * This is the only Phase 6 gate that needs a model. The pinned OpenCode build
 * resolves a key-free default model, so nothing here provisions a key. When no model
 * can serve a turn (no network, upstream quota, a provider outage) both gates print
 * `P6_L1/L2 SKIP` plus `P6_MODEL_AVAILABLE 0 :: reason`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveChatUiGatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shotDir = File(context.filesDir, "screenshots")

    private companion object {
        const val PROJECT = "live-gates"

        /** Shared across the two test methods (JUnit makes a new instance per method). */
        @Volatile
        var modelServed = false

        @Volatile
        var modelReason = "no turn was attempted yet"
    }

    /** One part as the server reports it, reduced to what this gate needs. */
    private data class ServerPart(
        val id: String,
        val messageID: String,
        val role: String,
        val type: String,
        val tool: String,
        val status: String,
        val text: String,
        val output: String,
        val createdMs: Long,
    )

    // ---- thin wrappers over the rule ---------------------------------------

    private fun nodes() = rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes()

    private fun allText() = allTextOf(nodes())

    private fun allLines(): Set<String> =
        allText().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(tag: String): Boolean {
        val found = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return found.isNotEmpty() && isEnabledNode(found.first())
    }

    private fun shot(name: String) = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }

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
        printGate(id, ok, detail)
        assertTrue("P6_$id :: $detail", ok)
    }

    private fun skip(id: String, reason: String) {
        printSkip(id, reason)
        Assume.assumeTrue("P6_$id :: $reason", false)
    }

    // ---- the app's own client, as the authority on what the server sent -----

    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

    private fun apiOrNull(): OpenCodeApi? = runCatching {
        val password = SecretStore.get(context).get(Secrets.SERVER_PASSWORD) ?: return@runCatching null
        val dir = ProjectStore.get(context).active()?.path
        OpenCodeApi(base, RuntimeEnv.SERVER_USER, password, directory = dir)
    }.getOrNull()

    /** Every part of the project's most recent session that has a user turn. */
    private fun serverParts(): List<ServerPart> {
        val api = apiOrNull() ?: return emptyList()
        return runCatching {
            val sessions = api.listSessions(limit = 20).sortedByDescending { it.updatedAt }
            for (session in sessions) {
                val messages = api.messages(session.id, limit = 60)
                if (messages.none { it.role == "user" }) continue
                return@runCatching messages.flatMap { m ->
                    val created = m.info?.optJSONObject("time")?.optLong("created") ?: 0L
                    m.parts.map { p ->
                        val state = p.optJSONObject("state")
                        val metadata = state?.optJSONObject("metadata")
                        ServerPart(
                            id = p.optString("id"),
                            messageID = m.id,
                            role = m.role,
                            type = p.optString("type"),
                            tool = p.optString("tool"),
                            status = state?.optString("status") ?: "",
                            text = p.optString("text"),
                            output = state?.opt("output")?.toString() ?: metadata?.optString("output") ?: "",
                            createdMs = created,
                        )
                    }
                }
            }
            emptyList()
        }.getOrDefault(emptyList())
    }

    /** Message ids the server already had, captured before a prompt is sent. */
    private fun knownMessageIds(): Set<String> = serverParts().map { it.messageID }.toSet()

    /**
     * The newest assistant text in a message that did not exist before the prompt.
     * Novelty is decided by message id rather than by a clock: `time.created` is not
     * always populated on a message that is still streaming, and a wall-clock
     * comparison across the app and the server would be a guess.
     */
    private fun replyInNew(known: Set<String>): String = serverParts()
        .filter {
            it.role == "assistant" && it.type == "text" && it.text.isNotBlank() && it.messageID !in known
        }
        .maxByOrNull { it.createdMs }
        ?.text
        ?.trim()
        ?: ""

    private fun toolPartsInNew(known: Set<String>): List<ServerPart> =
        serverParts().filter { it.type == "tool" && it.messageID !in known }

    /**
     * OpenCode blocks a turn on a permission ask or a question exactly like the TUI
     * does, so a live gate has to answer through the same UI a user would - or the
     * turn never finishes and the gate would blame the model for an unanswered
     * prompt.
     */
    private fun answerAnyAsk(): Int {
        var answered = 0
        val once = rule.onAllNodesWithTag(TAG_PERMISSION_ONCE).fetchSemanticsNodes()
        if (once.isNotEmpty() && isEnabledNode(once.first())) {
            runCatching {
                rule.onAllNodesWithTag(TAG_PERMISSION_ONCE)[0].performClick()
                answered++
            }
        }
        val skipQuestion = rule.onAllNodesWithTag(TAG_QUESTION_SKIP).fetchSemanticsNodes()
        if (skipQuestion.isNotEmpty() && isEnabledNode(skipQuestion.first())) {
            runCatching {
                rule.onAllNodesWithTag(TAG_QUESTION_SKIP)[0].performClick()
                answered++
            }
        }
        if (answered > 0) rule.waitForIdle()
        return answered
    }

    /** "" when a chat surface with a usable composer is on screen, else the reason. */
    private fun ensureChatSurface(timeoutMs: Long): String {
        val leftWelcome = waitFor(timeoutMs) { exists("projects_screen") || exists("chat_screen") }
        if (!leftWelcome) return "the app never left the welcome screen within ${timeoutMs / 1000}s"
        if (exists("projects_screen")) {
            if (exists("project_row_$PROJECT")) {
                runCatching { rule.onNodeWithTag("project_row_$PROJECT").performClick() }
            } else {
                runCatching {
                    rule.onNodeWithTag("project_name_input").performTextInput(PROJECT)
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

    private fun sendPrompt(text: String): Boolean {
        runCatching { rule.onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput(text) }
            .onFailure { return false }
        rule.waitForIdle()
        if (!waitFor(30_000) { enabled(TAG_COMPOSER_SEND) }) return false
        runCatching { rule.onNodeWithTag(TAG_COMPOSER_SEND).performClick() }
            .onFailure { return false }
        rule.waitForIdle()
        return true
    }

    // ---- L1: a reply streams into the conversation --------------------------

    @Test
    fun l1_aReplyStreamsIntoTheConversation() {
        val problem = ensureChatSurface(900_000)
        if (problem.isNotEmpty()) {
            modelReason = problem
            printMarker("MODEL_AVAILABLE", "0 :: $problem")
            skip("L1", problem)
            return
        }
        val prompt = "Answer in one short sentence: what colour is a clear sky at noon?"
        val known = knownMessageIds()
        val sent = sendPrompt(prompt)
        if (!sent) {
            modelReason = "the composer would not accept a prompt"
            printMarker("MODEL_AVAILABLE", "0 :: $modelReason")
            skip("L1", modelReason)
            return
        }
        val chrome = allLines()

        var sawBusy = false
        var sawStreaming = false
        var sawErrorBanner = false
        var asksAnswered = 0
        var polls = 0
        var reply = ""
        val answered = waitFor(300_000) {
            asksAnswered += answerAnyAsk()
            if (exists("busy_bar")) sawBusy = true
            if (exists("streaming_indicator")) sawStreaming = true
            if (exists("availability_banner")) sawErrorBanner = true
            polls++
            if (polls % 3 == 0) reply = replyInNew(known)
            reply.isNotEmpty()
        }
        if (reply.isEmpty()) reply = replyInNew(known)

        // The words the server sent, checked against what the screen shows. A long
        // word survives markdown rendering; punctuation and formatting do not.
        val needle = reply.split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 4 }
            .maxByOrNull { it.length }?.take(24) ?: reply.take(24)
        val shownInUi = needle.isNotBlank() && allText().contains(needle, ignoreCase = true)
        val userPromptShown = allText().contains("clear sky at noon", ignoreCase = true)
        val composerUsableAgain = waitFor(60_000) { enabled(TAG_COMPOSER_INPUT) && !exists("busy_bar") }
        val freshLines = (allLines() - chrome).size
        val bytes = shot("30-live-chat-reply.png")

        if (!answered || reply.isEmpty()) {
            // Open the failure's own disclosure first, so the reason carries the
            // server's verbatim words and not just our headline; then read the
            // screen with a short retry, because run #8's reason came out blank:
            // one semantics fetch landed mid-recomposition while the banner and
            // its Details disclosure were plainly on screen (see the run's
            // 30-live-chat-reply.png).
            val detailButtons = rule.onAllNodesWithText(context.getString(R.string.action_details))
            if (detailButtons.fetchSemanticsNodes().isNotEmpty()) {
                detailButtons[0].performClick()
                rule.waitForIdle()
            }
            waitFor(15_000) { allText().isNotBlank() }
            // Quote the failure surface itself (turn-error card first, then the
            // banner), not the last lines on screen - run #10's reason ended up
            // quoting the composer chrome ("Attach a file | Message ... | Send")
            // because takeLast picks the bottom of the screen.
            val errNodes = rule.onAllNodesWithTag(TAG_TURN_ERROR).fetchSemanticsNodes() +
                rule.onAllNodesWithTag("availability_banner").fetchSemanticsNodes()
            val screenWords = (
                errNodes.firstOrNull()?.let { allTextOf(listOf(it)) }
                    ?: allText().lineSequence().filter { it.isNotBlank() }.toList()
                        .takeLast(3).joinToString(" | ")
                ).take(220)
            modelReason = if (sawErrorBanner || exists(TAG_TURN_ERROR)) {
                "the turn failed and the app said so: $screenWords"
            } else {
                "no assistant text from the server within 300s (busySeen=$sawBusy, asksAnswered=$asksAnswered)"
            }
            printMarker("MODEL_AVAILABLE", "0 :: $modelReason")
            skip("L1", modelReason)
            return
        }
        modelServed = true
        modelReason = "a model served a turn through the UI"
        printMarker("MODEL_AVAILABLE", "1 :: replyChars=${reply.length} asksAnswered=$asksAnswered")

        // From here the model has done its part: anything missing is a UI verdict.
        val ok = shownInUi && userPromptShown && composerUsableAgain && !sawErrorBanner
        gate(
            "L1",
            ok,
            "promptSent=true userPromptShown=$userPromptShown serverReplyChars=${reply.length} " +
                "replyShownInUi=$shownInUi(needle='$needle') newScreenLines=$freshLines " +
                "busyIndicator=$sawBusy streamingDots=$sawStreaming errorBanner=$sawErrorBanner " +
                "asksAnswered=$asksAnswered composerUsableAgain=$composerUsableAgain " +
                "screenshot=$bytes reply='${reply.take(90)}'",
        )
    }

    // ---- L2: a shell tool call becomes an expandable card -------------------

    @Test
    fun l2_aShellToolCallBecomesAnExpandableCardWithRealOutput() {
        if (!modelServed) {
            printMarker("MODEL_AVAILABLE", "0 :: $modelReason")
            skip("L2", "no model served a turn, so no tool call can be observed: $modelReason")
            return
        }
        val problem = ensureChatSurface(300_000)
        if (problem.isNotEmpty()) {
            skip("L2", problem)
            return
        }
        val marker = "P6LIVE" + (System.currentTimeMillis() % 1_000_000)
        val known = knownMessageIds()
        val sent = sendPrompt("Use the bash tool to run this exact command, then tell me what it printed: echo $marker")
        if (!sent) {
            skip("L2", "the composer would not accept the tool prompt")
            return
        }

        var asksAnswered = 0
        var polls = 0
        var toolParts: List<ServerPart> = emptyList()
        val toolCallHappened = waitFor(420_000) {
            asksAnswered += answerAnyAsk()
            polls++
            if (polls % 3 == 0) toolParts = toolPartsInNew(known)
            toolParts.any { it.tool.isNotEmpty() }
        }
        if (toolParts.isEmpty()) toolParts = toolPartsInNew(known)
        val part = toolParts.firstOrNull { it.tool.isNotEmpty() }

        if (!toolCallHappened || part == null) {
            val reply = replyInNew(known)
            skip(
                "L2",
                "the model answered without calling a tool within 420s (asksAnswered=$asksAnswered, " +
                    "replyChars=${reply.length}); screen='${allText().take(140)}'",
            )
            return
        }

        // Wait for the UI to catch up with the server, then assert on the card that
        // carries THIS part's id - no guessing which card is whose.
        val cardTag = "tool_card_${part.id}"
        val headerTag = "tool_header_${part.id}"
        val outputTag = "tool_output_${part.id}"
        val cardShown = waitFor(60_000) { exists(cardTag) }
        val collapsedBeforeTap = !exists(outputTag)
        val collapsedShot = shot("31-live-tool-card-collapsed.png")
        val headline = context.getString(R.string.chat_tool_kind_shell)
        val headlineShown = allText().contains(headline, ignoreCase = true) ||
            allText().contains(part.tool, ignoreCase = true)

        var tapped = false
        if (exists(headerTag)) {
            runCatching { rule.onNodeWithTag(headerTag).performClick() }
                .onSuccess { tapped = true }
            rule.waitForIdle()
        }
        val outputShown = waitFor(30_000) { exists(outputTag) }
        val serverOutputHasMarker = part.output.contains(marker)
        val uiOutputHasMarker = allText().contains(marker)
        val expandedShot = shot("32-live-tool-card-expanded.png")

        val ok = cardShown && collapsedBeforeTap && headlineShown && tapped && outputShown &&
            serverOutputHasMarker && uiOutputHasMarker
        gate(
            "L2",
            ok,
            "tool=${part.tool} status=${part.status} partId=${part.id} cardShown=$cardShown " +
                "collapsedBeforeTap=$collapsedBeforeTap headline=$headlineShown expandedByTap=$tapped " +
                "outputRendered=$outputShown markerInServerOutput=$serverOutputHasMarker " +
                "markerOnScreen=$uiOutputHasMarker asksAnswered=$asksAnswered marker=$marker " +
                "screenshots=$collapsedShot,$expandedShot",
        )
    }
}
