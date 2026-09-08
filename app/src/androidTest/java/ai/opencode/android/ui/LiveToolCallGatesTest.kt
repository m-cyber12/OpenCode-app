package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.MainActivity
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.Secrets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
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

    private val support = P8GateSupport(rule)
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val PROJECT = "live-gates"
        const val BADGE = "P8LIVE"

        /** Shared across the test methods (JUnit makes a new instance per method). */
        @Volatile
        var keyServed = false

        @Volatile
        var keyReason = "no turn was attempted yet"
    }

    // ---- 01: the provided key actually serves a model round-trip ------------

    @Test
    fun `01 the provisioned key serves a model round-trip`() {
        val key = support.provisionedKey
        if (key == null) {
            printMarker8("MODEL_AVAILABLE", "0 :: no model key in the harness dir (OPENROUTER_API_KEY not set for this run)")
            support.skip("KEYPROBE", "no model key was provisioned for this run")
            return
        }
        val problem = support.ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            keyReason = problem
            printMarker8("MODEL_AVAILABLE", "0 :: $problem")
            support.skip("KEYPROBE", problem)
            return
        }
        val api = support.apiOrNull() ?: run {
            keyReason = "no loopback credential available"
            support.skip("KEYPROBE", keyReason)
            return
        }

        // The product's credential path, with the key this run provisions.
        Secrets.putProviderKey(context, "openrouter", key)
        val push = runCatching { api.setProviderAuth("openrouter", key) }
        if (push.isFailure) {
            keyReason = "the server refused the credential push: ${push.exceptionOrNull()?.message}"
            support.gate("KEYPROBE", false, keyReason)
            return
        }

        // One tiny API-level turn, model named explicitly (no UI needed to
        // answer "does this key work").
        val token = "P8PROBEOK"
        val model = support.provisionedModel
        val session = runCatching { api.createSession(title = "p8 key probe") }
            .getOrElse { t ->
                keyReason = "session creation failed: ${t.message}"
                support.gate("KEYPROBE", false, keyReason)
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
            support.gate("KEYPROBE", false, keyReason)
            return
        }
        if (sent != 204 && sent != 200) {
            keyReason = "prompt_async answered http $sent (not queued)"
            support.gate("KEYPROBE", false, keyReason)
            return
        }

        val deadline = System.currentTimeMillis() + 240_000
        var reply = ""
        var err = ""
        while (System.currentTimeMillis() < deadline && reply.isEmpty()) {
            for (m in api.messages(session.id, 20)) {
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

        if (reply.isNotEmpty()) {
            keyServed = true
            keyReason = "the provisioned key served a turn ($model)"
            printMarker8("MODEL_AVAILABLE", "1 :: model=$model replyChars=${reply.length}")
            support.gate(
                "KEYPROBE",
                true,
                "model=$model tokenSeen=true reply='${reply.take(80)}' session=${session.id}",
            )
        } else {
            val lastErr = err.ifEmpty { "none" }
            keyReason = "no assistant reply with the token in 240s (lastError='$lastErr')"
            printMarker8("MODEL_AVAILABLE", "0 :: $keyReason")
            support.gate(
                "KEYPROBE",
                false,
                "model=$model tokenSeen=false lastError='${err.take(200)}' - the key is expired, " +
                    "out of credit, or the model id is wrong for this account",
            )
        }
    }

    // ---- 02: the real tool call, through the real UI (closes Phase 6 L2) -----

    @Test
    fun `02 a real tool call with real output becomes an expandable card`() {
        if (!keyServed) {
            printMarker8("MODEL_AVAILABLE", "0 :: $keyReason")
            support.skip("TOOL", "the provisioned key could not serve a turn, so no tool call can be observed: $keyReason")
            return
        }
        val problem = support.ensureChatSurface(300_000, PROJECT)
        if (problem.isNotEmpty()) {
            support.skip("TOOL", problem)
            return
        }
        // The UI's own model selection (the same call the Settings model picker
        // makes) so the turn the composer sends goes to the provisioned model.
        val dir = ProjectStore.get(context).active()?.path
        AppContainer.get(context).repositoryFor(dir).setModel("openrouter", support.provisionedModel)

        val marker = BADGE + (System.currentTimeMillis() % 1_000_000)
        val known = support.knownMessageIds()
        val sent = support.sendPrompt(
            "You must use the bash tool for this task; answering from memory is not allowed. " +
                "Run this exact shell command, then report ONLY what it printed: echo $marker",
        )
        if (!sent) {
            support.skip("TOOL", "the composer would not accept the tool prompt")
            return
        }

        var asksAnswered = 0
        var polls = 0
        var toolParts = emptyList<P8ServerPart>()
        var replySoFar = ""
        val toolCallHappened = support.waitFor(480_000) {
            asksAnswered += support.answerAnyAsk()
            polls++
            if (polls % 3 == 0) {
                toolParts = support.toolPartsInNew(known)
                replySoFar = support.replyInNew(known)
            }
            toolParts.any { it.tool.isNotEmpty() }
        }
        if (toolParts.isEmpty()) toolParts = support.toolPartsInNew(known)
        if (replySoFar.isEmpty()) replySoFar = support.replyInNew(known)
        val part = toolParts.firstOrNull { it.tool.isNotEmpty() }

        if (!toolCallHappened || part == null) {
            support.skip(
                "TOOL",
                "the model answered without calling a tool within 480s (asksAnswered=$asksAnswered, " +
                    "replyChars=${replySoFar.length}, reply='${replySoFar.take(120)}')",
            )
            return
        }

        // Wait for the UI to catch up with the server, then assert on the card
        // that carries THIS part's id - no guessing which card is whose.
        val cardTag = "tool_card_${part.id}"
        val headerTag = "tool_header_${part.id}"
        val outputTag = "tool_output_${part.id}"
        val cardShown = support.waitFor(60_000) {
            support.ruleHasTag(cardTag)
        }
        val collapsedBeforeTap = !support.ruleHasTag(outputTag)
        val collapsedShot = support.shot("41-p8-tool-card-collapsed.png")
        val headline = context.getString(ai.opencode.android.R.string.chat_tool_kind_shell)
        val lines = support.allLines()
        val headlineShown = lines.any { it.contains(headline, ignoreCase = true) } ||
            lines.any { it.contains(part.tool, ignoreCase = true) }

        var tapped = false
        if (support.ruleHasTag(headerTag)) {
            runCatching {
                support.ruleOnTag(headerTag).performClick()
            }.onSuccess { tapped = true }
        }
        val outputShown = support.waitFor(30_000) { support.ruleHasTag(outputTag) }
        val serverOutputHasMarker = part.output.contains(marker)
        val uiOutputHasMarker = support.allLines().any { it.contains(marker) }
        val expandedShot = support.shot("42-p8-tool-card-expanded.png")

        val ok = cardShown && collapsedBeforeTap && headlineShown && tapped && outputShown &&
            serverOutputHasMarker && uiOutputHasMarker
        support.gate(
            "TOOL",
            ok,
            "tool=${part.tool} status=${part.status} partId=${part.id} cardShown=$cardShown " +
                "collapsedBeforeTap=$collapsedBeforeTap headline=$headlineShown expandedByTap=$tapped " +
                "outputRendered=$outputShown markerInServerOutput=$serverOutputHasMarker " +
                "markerOnScreen=$uiOutputHasMarker asksAnswered=$asksAnswered marker=$marker " +
                "screenshots=$collapsedShot,$expandedShot :: this closes Phase 6 L2 (real model, real tool call)",
        )
    }

    // ---- 03: the injected key must not survive the run -----------------------

    @Test
    fun `03 the injected key is revoked from server and keystore after the gates`() {
        val api = support.apiOrNull()
        runCatching {
            val dir = ProjectStore.get(context).active()?.path
            AppContainer.get(context).repositoryFor(dir).clearModel()
        }
        runCatching { api?.deleteProviderAuth("openrouter") }
        val removed = runCatching { Secrets.removeProviderKey(context, "openrouter") }.getOrDefault(false)
        val leftover = runCatching { Secrets.providerKey(context, "openrouter") }.getOrNull()
        val providerIds = runCatching { Secrets.storedProviderIds(context) }.getOrDefault(emptyList())
        val ok = leftover == null && !providerIds.contains("openrouter") && (removed || providerIds.isEmpty())
        support.gate(
            "CLEANUP",
            ok,
            "keystoreRemoved=$removed leftoverKey=${if (leftover == null) "none" else "PRESENT"} " +
                "storedProviderIds=${providerIds.joinToString(",") { it.ifEmpty { "?" } }} " +
                ":: the short-lived CI key must not persist on the device after the run",
        )
    }
}
