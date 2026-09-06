package ai.opencode.android.ui

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.projects.Project
import ai.opencode.android.runtime.RuntimeVersion
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.chat.SessionPanel
import ai.opencode.android.ui.chat.TAG_COMPOSER_INPUT
import ai.opencode.android.ui.chat.TAG_COMPOSER_SEND
import ai.opencode.android.ui.chat.TAG_COMPOSER_STOP
import ai.opencode.android.ui.chat.TAG_MESSAGE
import ai.opencode.android.ui.chat.TAG_PERMISSION_ALWAYS
import ai.opencode.android.ui.chat.TAG_PERMISSION_ASK
import ai.opencode.android.ui.chat.TAG_PERMISSION_ONCE
import ai.opencode.android.ui.chat.TAG_PERMISSION_REJECT
import ai.opencode.android.ui.chat.TAG_QUESTION_ASK
import ai.opencode.android.ui.chat.TAG_QUESTION_SKIP
import ai.opencode.android.ui.chat.TAG_QUESTION_SUBMIT
import ai.opencode.android.ui.chat.TAG_TOOL_CARD
import ai.opencode.android.ui.chat.TAG_TOOL_HEADER
import ai.opencode.android.ui.chat.TAG_TOOL_OUTPUT
import ai.opencode.android.ui.chat.TAG_TRANSCRIPT
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.ThemeChoice
import ai.opencode.android.ui.markdown.TAG_CODE_BODY
import ai.opencode.android.ui.projects.ProjectsScreen
import ai.opencode.android.ui.settings.SettingsScreen
import ai.opencode.android.ui.theme.OpenCodeTheme
import ai.opencode.android.ui.welcome.WelcomeScreen
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Phase 6 UI gates that need no runtime, no model and no API key.
 *
 * The screens are pure functions of state (a static check enforces that only
 * `ui/AppRoot.kt` may touch the runtime or the container), so every gate here
 * renders a real screen from fabricated server state and asserts on the semantics
 * tree that a screen reader - and this test - actually sees. Tool metadata,
 * permission asks, retry state and error shapes are upstream's own field values,
 * copied from the pinned commit rather than invented.
 *
 * Verdict discipline is Phase 5's: each gate prints `P6_<id> PASS|FAIL :: detail`
 * to stdout and logcat, and `phase6/scripts/20-ui-gates.sh` folds the deduplicated
 * union of both channels into GATES_SUMMARY.txt. A gate that never ran cannot be
 * mistaken for one that passed.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatUiGatesTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shotDir = File(context.filesDir, "screenshots")

    private companion object {
        const val SES = "ses_gate1"
        const val TAG = "OpenCode/gate"
    }

    // ---- recorded callbacks -------------------------------------------------

    private val sent = mutableListOf<String>()
    private val drafts = mutableListOf<String>()
    private val replies = mutableListOf<Pair<String, String>>()
    private val questionAnswers = mutableListOf<Pair<String, List<List<String>>>>()
    private val questionSkips = mutableListOf<String>()
    private var stops = 0
    private var retries = 0
    private var undos = 0
    private var redos = 0
    private var newSessions = 0
    private var openSessions = 0
    private var openProjects = 0
    private var openSettings = 0
    private var attaches = 0
    private val removedAttachments = mutableListOf<String>()
    private var dismissals = 0
    private val selectedSessions = mutableListOf<String>()
    private val deletedSessions = mutableListOf<String>()
    private val renamedSessions = mutableListOf<Pair<String, String>>()
    private var panelNew = 0
    private var panelBack = 0
    private val createdProjects = mutableListOf<String>()
    private val openedProjectList = mutableListOf<String>()
    private var settingsBack = 0
    private var restarts = 0
    private var shares = 0
    private var copies = 0
    private var welcomeContinue = 0
    private var welcomeSettings = 0

    // ---- fabrication helpers ------------------------------------------------

    private fun runtimeSummary(status: String, ready: Boolean, detail: String = "", restarts: Int = 0) =
        RuntimeSummary(
            status = status,
            detail = detail,
            restartCount = restarts,
            availability = UiError.classifyRuntimeStatus(status),
            ready = ready,
            opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
            payloadVersion = RuntimeVersion.PAYLOAD_VERSION,
            abi = "arm64-v8a",
            device = "Gate Device",
            androidVersion = "15 (API 35)",
            audit = "loopback-only bind, credential in Android Keystore",
        )

    /**
     * The supervisor's real healthy detail line, host and port included: if any of
     * it reached a conversation surface, the leak audit below would catch it.
     */
    private val healthy = runtimeSummary("HEALTHY", ready = true, detail = healthyDetailFixture())

    private fun textPart(id: String, messageID: String, text: String) = Transcript.Part(
        id = id,
        messageID = messageID,
        sessionID = SES,
        type = "text",
        text = text,
        tool = "",
        callID = "",
        status = "",
        input = "",
        output = "",
        title = "",
    )

    private fun toolPart(
        id: String,
        messageID: String,
        tool: String,
        status: String,
        title: String,
        input: String,
        output: String,
        metadata: String = "",
        error: String = "",
    ) = Transcript.Part(
        id = id,
        messageID = messageID,
        sessionID = SES,
        type = "tool",
        text = "",
        tool = tool,
        callID = "call_$id",
        status = status,
        input = input,
        output = output,
        title = title,
        metadata = metadata,
        error = error,
        timeStart = 1_700_000_000_000L,
        timeEnd = 1_700_000_001_500L,
    )

    private fun message(
        id: String,
        role: String,
        parts: List<Transcript.Part>,
        completed: Boolean = true,
        error: Transcript.TurnError? = null,
    ) = Transcript.Message(
        id = id,
        sessionID = SES,
        role = role,
        parts = parts,
        completed = completed,
        error = error,
        providerID = "opencode",
        modelID = "big-pickle",
    )

    private fun sessionView(
        messages: List<Transcript.Message> = emptyList(),
        busy: Boolean = false,
        pending: List<Transcript.Prompt> = emptyList(),
        questions: List<Transcript.Question> = emptyList(),
        error: Transcript.TurnError? = null,
        retry: Transcript.RetryInfo? = null,
    ) = Transcript.SessionView(
        sessionID = SES,
        busy = busy,
        messages = messages,
        pending = pending,
        questions = questions,
        error = error,
        retry = retry,
    )

    private fun uiState(
        view: Transcript.SessionView? = null,
        sessions: List<OpenCodeApi.SessionInfo> = emptyList(),
        busy: Boolean = false,
        error: String = "",
        errorKind: AgentAvailability = AgentAvailability.UNKNOWN,
        serverReachable: Boolean = true,
        draft: String = "",
        attachments: List<OpenCodeApi.Attachment> = emptyList(),
    ) = OpenCodeRepository.UiState(
        streaming = true,
        streamStatus = "open",
        serverVersion = "1.18.23",
        sessions = sessions,
        selectedSession = if (view == null) "" else view.sessionID,
        transcript = Transcript.Snapshot(if (view == null) emptyList() else listOf(view)),
        busy = busy,
        error = error,
        errorKind = errorKind,
        serverReachable = serverReachable,
        draft = draft,
        attachments = attachments,
    )

    // ---- rendering ----------------------------------------------------------

    private fun renderChat(
        state: OpenCodeRepository.UiState,
        availability: AgentAvailability,
        runtime: RuntimeSummary = healthy,
    ) {
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ChatScreen(
                    state = state,
                    runtime = runtime,
                    availability = availability,
                    projectName = "gates",
                    onSend = { sent.add(it) },
                    onDraftChange = { drafts.add(it) },
                    onStop = { stops++ },
                    onRetry = { retries++ },
                    onUndo = { undos++ },
                    onRedo = { redos++ },
                    onNewSession = { newSessions++ },
                    onOpenSessions = { openSessions++ },
                    onOpenProjects = { openProjects++ },
                    onOpenSettings = { openSettings++ },
                    onPermissionReply = { id, response -> replies.add(id to response) },
                    onQuestionSubmit = { id, answers -> questionAnswers.add(id to answers) },
                    onQuestionSkip = { questionSkips.add(it) },
                    onAttach = { attaches++ },
                    onRemoveAttachment = { removedAttachments.add(it) },
                    onDismissBanner = { dismissals++ },
                )
            }
        }
        rule.waitForIdle()
    }

    /** Same screen, but the message list is observable so a gate can grow it. */
    private fun renderLiveChat(messages: List<Transcript.Message>, busy: Boolean) {
        val live = mutableStateListOf<Transcript.Message>().also { it.addAll(messages) }
        val liveBusy = mutableStateOf(busy)
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ChatScreen(
                    state = uiState(sessionView(messages = live.toList(), busy = liveBusy.value), busy = liveBusy.value),
                    runtime = healthy,
                    availability = AgentAvailability.READY,
                    projectName = "gates",
                    onSend = { sent.add(it) },
                    onDraftChange = { drafts.add(it) },
                    onStop = { stops++ },
                    onRetry = { retries++ },
                    onUndo = { undos++ },
                    onRedo = { redos++ },
                    onNewSession = { newSessions++ },
                    onOpenSessions = { openSessions++ },
                    onOpenProjects = { openProjects++ },
                    onOpenSettings = { openSettings++ },
                    onPermissionReply = { id, response -> replies.add(id to response) },
                    onQuestionSubmit = { id, answers -> questionAnswers.add(id to answers) },
                    onQuestionSkip = { questionSkips.add(it) },
                    onAttach = { attaches++ },
                    onRemoveAttachment = { removedAttachments.add(it) },
                    onDismissBanner = { dismissals++ },
                )
            }
        }
        rule.waitForIdle()
        return live to liveBusy
    }

    private fun renderSessions(
        sessions: List<OpenCodeApi.SessionInfo>,
        transcript: Transcript.Snapshot = Transcript.Snapshot(emptyList()),
        selected: String = "",
    ) {
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionPanel(
                    sessions = sessions,
                    selectedSession = selected,
                    transcript = transcript,
                    onBack = { panelBack++ },
                    onSelect = { selectedSessions.add(it) },
                    onNew = { panelNew++ },
                    onDelete = { deletedSessions.add(it) },
                    onRename = { id, title -> renamedSessions.add(id to title) },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun renderProjects(projects: List<Project>, activeName: String = "") {
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectsScreen(
                    projects = projects,
                    activeName = activeName,
                    runtimeLine = context.getString(R.string.welcome_stage_healthy_title),
                    onCreate = { createdProjects.add(it) },
                    onOpen = { openedProjectList.add(it) },
                    onBack = { panelBack++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun renderWelcome(runtime: RuntimeSummary) {
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                WelcomeScreen(
                    runtime = runtime,
                    onContinue = { welcomeContinue++ },
                    onOpenSettings = { welcomeSettings++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun renderSettings(
        runtime: RuntimeSummary = healthy,
        state: OpenCodeRepository.UiState = uiState(),
        availability: AgentAvailability = AgentAvailability.READY,
    ) {
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SettingsScreen(
                    runtime = runtime,
                    state = state,
                    availability = availability,
                    diagnosticsLines = listOf("HEALTHY", "spawn ok", "bind 127.0.0.1 only"),
                    diagnosticsLoading = false,
                    storedProviderIds = "anthropic",
                    hardwareBacked = "software",
                    appVersion = "1.18.23-phase5 (6)",
                    theme = ThemeChoice.DARK,
                    dynamicColor = false,
                    onThemeChange = { },
                    onDynamicColorChange = { },
                    onSetModel = { _, _ -> },
                    onClearModel = { },
                    onSaveKey = { _, _ -> },
                    onRevokeKey = { },
                    onAddMcp = { _, _, _ -> },
                    onConnectMcp = { },
                    onDisconnectMcp = { },
                    onRefreshMcp = { },
                    onBashPolicy = { },
                    onShareDiagnostics = { shares++ },
                    onCopyDiagnostics = { copies++ },
                    onRefreshDiagnostics = { },
                    onRestartRuntime = { restarts++ },
                    onBack = { settingsBack++ },
                )
            }
        }
        rule.waitForIdle()
    }

    // ---- semantics helpers --------------------------------------------------

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun countTag(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /**
     * Compose 1.6 stores TestTag as a list (a node can carry several); read it as
     * `Any?` so this matcher is correct whichever shape the property has.
     */
    private fun prefixMatcher(prefix: String) = SemanticsMatcher("TestTag starts with '$prefix'") { node ->
        when (val raw: Any? = node.config.getOrNull(SemanticsProperties.TestTag)) {
            is String -> raw.startsWith(prefix)
            is List<*> -> raw.any { (it as? String)?.startsWith(prefix) == true }
            else -> false
        }
    }

    private fun countPrefix(prefix: String) = rule.onAllNodes(prefixMatcher(prefix)).fetchSemanticsNodes().size

    private fun textOf(cfg: SemanticsConfiguration): String = buildString {
        cfg.getOrNull(SemanticsProperties.ContentDescription)?.let { append(it.joinToString(" ")); append(' ') }
        cfg.getOrNull(SemanticsProperties.Text)?.let { append(it.joinToString(" ")); append(' ') }
        cfg.getOrNull(SemanticsProperties.EditableText)?.let { append(it); append(' ') }
        cfg.getOrNull(SemanticsProperties.StateDescription)?.let { append(it); append(' ') }
    }.trim()

    private fun textMatcher(needle: String) = SemanticsMatcher("text contains '$needle'") { node ->
        textOf(node.config).contains(needle, ignoreCase = true)
    }

    private fun countText(needle: String) = rule.onAllNodes(textMatcher(needle)).fetchSemanticsNodes().size

    private fun onScreenText(): String = rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes()
        .joinToString("\n") { textOf(it.config) }

    private fun sendEnabled(): Boolean {
        val nodes = rule.onAllNodesWithTag(TAG_COMPOSER_SEND).fetchSemanticsNodes()
        return nodes.isNotEmpty() && nodes.first().config.getOrNull(SemanticsProperties.Enabled) != false
    }

    private fun distinctColorsUnder(tag: String): Int {
        var best = 0
        for (n in rule.onAllNodesWithTag(tag).fetchSemanticsNodes()) {
            for (annotated in n.config.getOrNull(SemanticsProperties.Text) ?: emptyList()) {
                val colors = annotated.spanStyles.map { it.item.color }.distinct().size
                if (colors > best) best = colors
            }
        }
        return best
    }

    private fun hasEmphasis(needle: String): Boolean {
        for (n in rule.onAllNodes(textMatcher(needle)).fetchSemanticsNodes()) {
            for (annotated in n.config.getOrNull(SemanticsProperties.Text) ?: emptyList()) {
                if (annotated.spanStyles.any { it.item.fontWeight != null && it.item.fontWeight != FontWeight.Normal }) {
                    return true
                }
            }
        }
        return false
    }

    /** Screenshot into the app's own filesDir; the gate script pulls them with run-as. */
    private fun shot(name: String) {
        val bytes = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }
        println("P6_SCREENSHOT $name $bytes bytes")
    }

    private fun gate(id: String, ok: Boolean, detail: String = "") {
        val line = "P6_$id ${if (ok) "PASS" else "FAIL"} :: $detail"
        Log.i(TAG, line)
        println(line)
        assertTrue(line, ok)
    }

    // ---- U1: a long transcript renders lazily -------------------------------

    @Test
    fun u1_longTranscriptRendersLazilyAndFollowsTheReply() {
        val rows = (1..300).flatMap { i ->
            listOf(
                message("msg_u$i", "user", listOf(textPart("p_u$i", "msg_u$i", "question number $i"))),
                message("msg_a$i", "assistant", listOf(textPart("p_a$i", "msg_a$i", "answer number $i"))),
            )
        }
        val (live, _) = renderLiveChat(rows, busy = false)
        rule.waitForIdle()

        val composedAtBottom = countPrefix("${TAG_MESSAGE}_")
        val newestVisible = exists("${TAG_MESSAGE}_msg_a300")

        rule.onNodeWithTag(TAG_TRANSCRIPT).performScrollToIndex(0)
        rule.waitForIdle()
        val composedAtTop = countPrefix("${TAG_MESSAGE}_")
        val oldestVisible = exists("${TAG_MESSAGE}_msg_u1")
        val newestGone = !exists("${TAG_MESSAGE}_msg_a300")

        // Scrolled away from the bottom, two more messages arrive: the transcript
        // must not yank the reader back, but must say how much they missed.
        live.add(message("msg_u301", "user", listOf(textPart("p_u301", "msg_u301", "late question"))))
        live.add(message("msg_a301", "assistant", listOf(textPart("p_a301", "msg_a301", "late answer"))))
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        val jumpShown = exists("jump_to_latest")
        val jumpLabel = context.getString(R.string.chat_new_messages, 2)
        if (jumpShown) {
            rule.onNodeWithTag("jump_to_latest").performClick()
            rule.waitForIdle()
        }
        val backAtBottom = exists("${TAG_MESSAGE}_msg_a301")

        shot("10-chat-long-transcript.png")
        val lazy = composedAtTop < 60 && composedAtBottom < 60
        gate(
            "U1",
            lazy && newestVisible && oldestVisible && newestGone && jumpShown && backAtBottom,
            "rows=602 composedAtBottom=$composedAtBottom composedAtTop=$composedAtTop " +
                "newestVisible=$newestVisible oldestVisible=$oldestVisible newestUncomposed=$newestGone " +
                "jumpToLatest=$jumpShown(label='$jumpLabel') jumpedToNewest=$backAtBottom",
        )
    }

    // ---- U2: tool-call cards ------------------------------------------------

    @Test
    fun u2_toolCardsReadNaturallyAndExpandToRealOutput() {
        val shellOutput = "total 24\ndrwxr-xr-x  4 u u 4096 Nov 14 09:12 .\n-rw-r--r--  1 u u  318 Nov 14 09:12 build.gradle.kts"
        val shellMeta = JSONObject()
            .put("output", shellOutput)
            .put("exit", 0)
            .put("truncated", false)
            .toString()
        val patch = "@@ -1,2 +1,3 @@\n-old line\n+new line\n+added line\n"
        val editMeta = JSONObject()
            .put("diff", patch)
            .put(
                "filediff",
                JSONObject().put("file", "src/Main.kt").put("patch", patch).put("additions", 2).put("deletions", 1),
            )
            .put("diagnostics", JSONObject().put("src/Main.kt", org.json.JSONArray().put(JSONObject().put("message", "unused"))))
            .toString()
        val shell = toolPart(
            id = "prt_shell",
            messageID = "msg_a1",
            tool = "bash",
            status = "completed",
            title = "ls -la",
            input = """{"command":"ls -la","description":"list the project"}""",
            output = shellOutput,
            metadata = shellMeta,
        )
        val edit = toolPart(
            id = "prt_edit",
            messageID = "msg_a1",
            tool = "edit",
            status = "completed",
            title = "src/Main.kt",
            input = """{"filePath":"src/Main.kt","oldString":"old line","newString":"new line"}""",
            output = "",
            metadata = editMeta,
        )
        val failed = toolPart(
            id = "prt_fail",
            messageID = "msg_a2",
            tool = "bash",
            status = "error",
            title = "nope --version",
            input = """{"command":"nope --version"}""",
            output = "",
            error = "command not found: nope",
        )
        val assistant = message("msg_a1", "assistant", listOf(shell, edit))
        val failedTurn = message("msg_a2", "assistant", listOf(failed), error = null)
        renderChat(uiState(sessionView(messages = listOf(message("msg_u1", "user", listOf(textPart("p", "msg_u1", "list the project"))), assistant, failedTurn))))
        rule.waitForIdle()

        val shellHeadline = context.getString(R.string.chat_tool_kind_shell) + " \u00b7 bash"
        val editHeadline = context.getString(R.string.chat_tool_kind_edit) + " \u00b7 edit"
        val collapsedShell = !exists("$TAG_TOOL_OUTPUT" + "_prt_shell")
        val collapsedEdit = !exists("$TAG_TOOL_OUTPUT" + "_prt_edit")
        val headlineShell = countText(shellHeadline) > 0
        val headlineEdit = countText(editHeadline) > 0
        val statusDone = countText(context.getString(R.string.chat_tool_status_completed)) >= 2
        val expandLabel = context.getString(R.string.chat_tool_expand)

        rule.onNodeWithTag("$TAG_TOOL_HEADER" + "_prt_shell").performClick()
        rule.waitForIdle()
        val shellExpanded = exists("$TAG_TOOL_OUTPUT" + "_prt_shell")
        val outputShown = onScreenText().contains("build.gradle.kts")
        val inputShown = onScreenText().contains("ls -la")
        val exitShown = onScreenText().contains(context.getString(R.string.chat_tool_exit_code, "0"))
        val collapseLabel = countText(context.getString(R.string.chat_tool_collapse)) > 0

        rule.onNodeWithTag("$TAG_TOOL_HEADER" + "_prt_edit").performClick()
        rule.waitForIdle()
        val diffSummary = onScreenText().contains(context.getString(R.string.chat_tool_diff_summary, 2, 1))
        val diffFile = onScreenText().contains("src/Main.kt")
        val diffBody = onScreenText().contains("+new line")
        val diagnostics = onScreenText().contains(context.getString(R.string.chat_tool_diagnostics, 1))

        // A failed tool starts expanded: a failure is never one tap deeper than a success.
        val failedExpanded = exists("$TAG_TOOL_OUTPUT" + "_prt_fail") || onScreenText().contains("command not found: nope")
        val failedStatus = countText(context.getString(R.string.chat_tool_status_error)) > 0

        shot("11-chat-tool-cards.png")
        val ok = collapsedShell && collapsedEdit && headlineShell && headlineEdit && statusDone &&
            shellExpanded && outputShown && inputShown && exitShown && collapseLabel &&
            diffSummary && diffFile && diffBody && diagnostics && failedExpanded && failedStatus &&
            expandLabel.isNotEmpty()
        gate(
            "U2",
            ok,
            "collapsedByDefault=$collapsedShell/$collapsedEdit headline=$headlineShell/$headlineEdit " +
                "expandedShowsOutput=$shellExpanded output=$outputShown input=$inputShown exit=$exitShown " +
                "diff=$diffSummary/$diffFile/$diffBody diagnostics=$diagnostics " +
                "failedCardOpen=$failedExpanded failedStatus=$failedStatus",
        )
    }

    // ---- U3: permission and question asks -----------------------------------

    @Test
    fun u3_permissionAndQuestionAsksTakeAllThreeReplies() {
        val prompt = Transcript.Prompt(
            id = "per_gate1",
            sessionID = SES,
            permission = "bash",
            patterns = listOf("git push*"),
            metadata = JSONObject().put("command", "git push origin main").toString(),
            always = listOf("git push"),
            toolCallID = "call_gate1",
        )
        val question = Transcript.Question(
            id = "que_gate1",
            sessionID = SES,
            items = listOf(
                Transcript.QuestionItem(
                    header = "Deploy",
                    question = "Which target should this go to?",
                    options = listOf(
                        Transcript.QuestionOption("staging", "the staging box"),
                        Transcript.QuestionOption("prod", "production"),
                    ),
                    multiple = false,
                    custom = true,
                ),
            ),
        )
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(message("msg_u1", "user", listOf(textPart("p", "msg_u1", "ship it")))),
                    busy = true,
                    pending = listOf(prompt),
                    questions = listOf(question),
                ),
                busy = true,
            ),
            availability = AgentAvailability.READY,
        )
        rule.waitForIdle()

        val askShown = exists("$TAG_PERMISSION_ASK" + "_per_gate1")
        val commandVerbatim = onScreenText().contains("git push origin main")
        val alwaysScope = onScreenText().contains(context.getString(R.string.ask_permission_always_scope, "git push"))
        val kindLine = onScreenText().contains(context.getString(R.string.ask_permission_kind_bash))
        val questionShown = exists("$TAG_QUESTION_ASK" + "_que_gate1")
        val questionText = onScreenText().contains("Which target should this go to?")
        val optionShown = onScreenText().contains("the staging box")

        rule.onNodeWithTag(TAG_PERMISSION_ONCE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PERMISSION_ALWAYS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PERMISSION_REJECT).performClick()
        rule.waitForIdle()
        val onceOk = replies.contains("per_gate1" to "once")
        val alwaysOk = replies.contains("per_gate1" to "always")
        val rejectOk = replies.contains("per_gate1" to "reject")

        // The question card: pick an option (submit is disabled until one is
        // picked), then send it. Upstream's question tool blocks the turn, so the
        // answer has to travel back through the same call the TUI makes.
        val options = rule.onAllNodes(textMatcher("staging")).fetchSemanticsNodes()
        val picked = options.isNotEmpty()
        if (picked) {
            rule.onAllNodes(textMatcher("staging"))[0].performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(TAG_QUESTION_SUBMIT).performClick()
        rule.waitForIdle()
        val answered = questionAnswers.any { it.first == "que_gate1" && it.second.any { a -> a.contains("staging") } }

        // Skip is the other honest answer.
        renderChat(
            uiState(sessionView(messages = emptyList(), busy = true, questions = listOf(question)), busy = true),
            availability = AgentAvailability.READY,
        )
        rule.onNodeWithTag(TAG_QUESTION_SKIP).performClick()
        rule.waitForIdle()
        val skipped = questionSkips.contains("que_gate1")

        shot("12-chat-asks.png")
        val ok = askShown && commandVerbatim && alwaysScope && kindLine && onceOk && alwaysOk && rejectOk &&
            questionShown && questionText && optionShown && picked && answered && skipped
        gate(
            "U3",
            ok,
            "ask=$askShown commandVerbatim=$commandVerbatim alwaysScope=$alwaysScope kind=$kindLine " +
                "once=$onceOk always=$alwaysOk reject=$rejectOk question=$questionShown " +
                "questionText=$questionText optionShown=$optionShown optionClicked=$picked " +
                "answered=$answered skipped=$skipped",
        )
    }

    // ---- U4: degraded states are distinguishable ----------------------------

    @Test
    fun u4_offlineAndDegradedStatesAreDistinguishable() {
        val downRuntime = runtimeSummary("FATAL", ready = false, detail = "spawn failed: exit 1")
        val startingRuntime = runtimeSummary("EXTRACTING", ready = false, detail = "unpacking payload")

        // (a) the local agent itself is not running
        renderChat(
            uiState(serverReachable = false, errorKind = AgentAvailability.SERVER_UNREACHABLE, draft = "hello"),
            availability = AgentAvailability.RUNTIME_UNAVAILABLE,
            runtime = downRuntime,
        )
        val aText = onScreenText()
        val aHeadline = aText.contains(context.getString(R.string.availability_runtime_unavailable))
        val aBody = aText.contains(context.getString(R.string.availability_runtime_unavailable_body))
        val aSend = sendEnabled()
        shot("13-chat-runtime-down.png")

        // (b) the agent is up but its own server is not answering
        renderChat(
            uiState(serverReachable = false, errorKind = AgentAvailability.SERVER_UNREACHABLE, draft = "hello"),
            availability = AgentAvailability.SERVER_UNREACHABLE,
        )
        val bText = onScreenText()
        val bHeadline = bText.contains(context.getString(R.string.availability_server_unreachable))
        val bSend = sendEnabled()

        // (c) the agent and its server are fine; the model service rejected the key
        val authError = Transcript.TurnError(
            name = "ProviderAuthError",
            message = "invalid api key",
            statusCode = 401,
            atMs = System.currentTimeMillis(),
        )
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(message("msg_u1", "user", listOf(textPart("p", "msg_u1", "hello")))),
                    error = authError,
                ),
                serverReachable = true,
                draft = "hello",
            ),
            availability = AgentAvailability.PROVIDER_AUTH,
        )
        val cText = onScreenText()
        val cHeadline = cText.contains(context.getString(R.string.availability_provider_auth))
        val cRawKept = cText.contains("ProviderAuthError") && cText.contains("invalid api key")
        val cSend = sendEnabled()
        shot("14-chat-provider-auth.png")

        // (d) still starting: not an error, and the composer says so
        renderChat(
            uiState(serverReachable = false, draft = "hello"),
            availability = AgentAvailability.RUNTIME_STARTING,
            runtime = startingRuntime,
        )
        val dText = onScreenText()
        val dHeadline = dText.contains(context.getString(R.string.availability_runtime_starting))
        val dSend = sendEnabled()

        val headlines = listOf(
            context.getString(R.string.availability_runtime_unavailable),
            context.getString(R.string.availability_server_unreachable),
            context.getString(R.string.availability_provider_auth),
            context.getString(R.string.availability_runtime_starting),
        )
        val distinct = headlines.distinct().size == headlines.size
        // The supervisor's own detail carries the loopback bind address once healthy;
        // the conversation surface must never show a host, a port or a URL.
        val leaks = (leakedTokens(aText) + leakedTokens(bText) + leakedTokens(cText) + leakedTokens(dText)).distinct()

        // The welcome screen is the other surface a first-run user reads, and a
        // working emulator only ever walks through three of its states. Render all
        // of them here - each with the real bind address in the supervisor's detail -
        // and require that every state says something different, in words, with no
        // connection detail in it.
        val stageTitles = listOf(
            "EXTRACTING" to R.string.welcome_stage_extracting_title,
            "STARTING" to R.string.welcome_stage_starting_title,
            "HEALTHY" to R.string.welcome_stage_healthy_title,
            "CRASHED_RESTARTING" to R.string.welcome_stage_crashed_title,
            "STOPPED" to R.string.welcome_stage_stopped_title,
            "FATAL" to R.string.welcome_stage_fatal_title,
            "UNSUPPORTED_DEVICE" to R.string.welcome_stage_unsupported_title,
        )
        val welcomeShown = mutableListOf<String>()
        val welcomeLeaks = mutableListOf<String>()
        for ((status, titleRes) in stageTitles) {
            renderWelcome(
                runtimeSummary(
                    status,
                    ready = status == "HEALTHY",
                    detail = healthyDetailFixture(),
                    restarts = if (status == "CRASHED_RESTARTING") 2 else 0,
                ),
            )
            val text = onScreenText()
            if (text.contains(context.getString(titleRes), ignoreCase = true)) welcomeShown += status
            welcomeLeaks += leakedTokens(text).map { "$status:$it" }
        }
        shot("19b-welcome-states.png")

        val ok = aHeadline && aBody && bHeadline && cHeadline && cRawKept && dHeadline && distinct &&
            !aSend && !bSend && !dSend && cSend && leaks.isEmpty() &&
            welcomeShown.size == stageTitles.size && welcomeLeaks.isEmpty()
        gate(
            "U4",
            ok,
            "runtimeDown=$aHeadline(body=$aBody,send=$aSend) serverUnreachable=$bHeadline(send=$bSend) " +
                "providerAuth=$cHeadline(rawKept=$cRawKept,send=$cSend) starting=$dHeadline(send=$dSend) " +
                "headlinesDistinct=$distinct chatLeaks=$leaks " +
                "welcomeStatesShown=${welcomeShown.size}/${stageTitles.size} welcomeLeaks=$welcomeLeaks",
        )
    }

    // ---- U5: streaming, stop, retry, regenerate ----------------------------

    @Test
    fun u5_streamingStopRetryAndRegenerateAreVisible() {
        // Streaming: the last assistant message is incomplete and still growing.
        val streaming = message(
            "msg_a1",
            "assistant",
            listOf(textPart("p1", "msg_a1", "Here is the answer so f")),
            completed = false,
        )
        renderChat(
            uiState(sessionView(messages = listOf(message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "explain"))), streaming), busy = true), busy = true),
            availability = AgentAvailability.READY,
        )
        val busyBar = exists("busy_bar")
        val streamingDots = exists("streaming_indicator")
        val stopShown = exists(TAG_COMPOSER_STOP)
        val sendHidden = !exists(TAG_COMPOSER_SEND)
        val workingLabel = onScreenText().contains(context.getString(R.string.chat_working))
        rule.onNodeWithTag(TAG_COMPOSER_STOP).performClick()
        rule.waitForIdle()
        val stopped = stops == 1
        shot("15-chat-streaming.png")

        // Upstream is retrying on its own and says so: that is not a spinner.
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "explain")))),
                    busy = true,
                    retry = Transcript.RetryInfo(attempt = 2, message = "Retrying in 3s", nextMs = 0L),
                ),
                busy = true,
            ),
            availability = AgentAvailability.READY,
        )
        val retryBanner = exists("retry_banner")
        val retryText = onScreenText().contains(context.getString(R.string.chat_retrying, 2))
        val retryDetail = onScreenText().contains("Retrying in 3s")

        // Finished turn: retry (revert + re-prompt) and undo are offered, not invented.
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(
                        message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "explain"))),
                        message("msg_a1", "assistant", listOf(textPart("p1", "msg_a1", "the answer"))),
                    ),
                ),
                sessions = listOf(
                    OpenCodeApi.SessionInfo(
                        id = SES,
                        title = "explain",
                        directory = "/data/local",
                        updatedAt = System.currentTimeMillis(),
                        revertMessageID = "msg_a1",
                    ),
                ),
            ),
            availability = AgentAvailability.READY,
        )
        val retryAction = exists("message_retry")
        val undoAction = exists("message_undo")
        val redoAction = exists("redo_turn")
        if (retryAction) rule.onNodeWithTag("message_retry").performClick()
        if (undoAction) rule.onNodeWithTag("message_undo").performClick()
        if (redoAction) rule.onNodeWithTag("redo_turn").performClick()
        rule.waitForIdle()
        val acted = retries == 1 && undos == 1 && redos == 1

        // A turn error is shown with the server's own words, and can be dismissed.
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "explain")))),
                    error = Transcript.TurnError(name = "APIError", message = "overloaded", statusCode = 529, retryable = true),
                ),
                error = "",
                errorKind = AgentAvailability.PROVIDER_UNREACHABLE,
            ),
            availability = AgentAvailability.PROVIDER_UNREACHABLE,
        )
        val turnErrorShown = onScreenText().contains("APIError") && onScreenText().contains("overloaded")

        val ok = busyBar && streamingDots && stopShown && sendHidden && workingLabel && stopped &&
            retryBanner && retryText && retryDetail && retryAction && undoAction && redoAction && acted && turnErrorShown
        gate(
            "U5",
            ok,
            "busyBar=$busyBar streamingIndicator=$streamingDots stopShown=$stopShown sendHidden=$sendHidden " +
                "workingLabel=$workingLabel stopCallback=$stopped retryBanner=$retryBanner " +
                "retryText=$retryText retryDetail=$retryDetail retryAction=$retryAction undoAction=$undoAction " +
                "redoAction=$redoAction callbacks=$acted turnErrorKept=$turnErrorShown",
        )
    }

    // ---- U6: markdown and syntax highlighting ------------------------------

    @Test
    fun u6_markdownAndSyntaxHighlightedCodeAreRendered() {
        val markdown = buildString {
            append("# Headline from the agent\n\n")
            append("Some **bold text** plus `inline code` and a plain paragraph.\n\n")
            append("- first bullet\n")
            append("- second bullet\n\n")
            append("> quoted line\n\n")
            append("```kotlin\n")
            append("fun main() {\n")
            append("    val greeting = \"hello there\"\n")
            append("    println(greeting) // prints it\n")
            append("}\n")
            append("```\n")
        }
        val partial = "Still writing:\n```bash\nls -la\n"
        renderChat(
            uiState(
                sessionView(
                    messages = listOf(
                        message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "show me code"))),
                        message("msg_a1", "assistant", listOf(textPart("p1", "msg_a1", markdown))),
                        message("msg_a2", "assistant", listOf(textPart("p2", "msg_a2", partial)), completed = false),
                    ),
                ),
            ),
            availability = AgentAvailability.READY,
        )
        rule.waitForIdle()

        val heading = countText("Headline from the agent") > 0
        val bold = countText("bold text") > 0 && hasEmphasis("bold text")
        val inlineCode = countText("inline code") > 0
        val bullets = countText("first bullet") > 0 && countText("second bullet") > 0
        val quote = countText("quoted line") > 0
        val codeBlocks = countTag("code_block")
        val codeBodies = countTag(TAG_CODE_BODY)
        val codeText = onScreenText().contains("val greeting")
        val colors = distinctColorsUnder(TAG_CODE_BODY)
        // The unterminated fence of a streaming reply must already read as code.
        val streamingAsCode = codeBlocks >= 2 && onScreenText().contains("ls -la")
        // No markdown syntax may survive into the rendered text.
        val rendered = onScreenText()
        val noSyntaxLeak = !rendered.contains("**bold text**") && !rendered.contains("```kotlin")

        shot("16-chat-markdown.png")
        val ok = heading && bold && inlineCode && bullets && quote && codeBlocks >= 2 && codeBodies >= 2 &&
            codeText && colors >= 3 && streamingAsCode && noSyntaxLeak
        gate(
            "U6",
            ok,
            "heading=$heading boldWithWeight=$bold inlineCode=$inlineCode bullets=$bullets quote=$quote " +
                "codeBlocks=$codeBlocks codeBodies=$codeBodies codeText=$codeText distinctColors=$colors " +
                "unterminatedFenceIsCode=$streamingAsCode noSyntaxLeak=$noSyntaxLeak",
        )
    }

    // ---- U7: accessibility semantics audit ---------------------------------

    @Test
    fun u7_everyInteractiveElementHasARealName() {
        val violations = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()

        fun audit(label: String) {
            var interactive = 0
            for (n in rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes()) {
                val cfg = n.config
                val isInteractive = cfg.contains(SemanticsActions.OnClick) ||
                    cfg.contains(SemanticsProperties.ToggleableState) ||
                    cfg.contains(SemanticsActions.SetText)
                if (!isInteractive) continue
                interactive++
                if (textOf(cfg).isBlank()) {
                    violations.add("$label#id=${n.id}:role=${cfg.getOrNull(SemanticsProperties.Role)}")
                }
            }
            counts[label] = interactive
        }

        val prompt = Transcript.Prompt(
            id = "per_a1",
            sessionID = SES,
            permission = "edit",
            patterns = listOf("*"),
            metadata = JSONObject().put("filePath", "src/Main.kt").toString(),
            always = emptyList(),
        )
        val question = Transcript.Question(
            id = "que_a1",
            sessionID = SES,
            items = listOf(
                Transcript.QuestionItem(
                    header = "Pick",
                    question = "Which one?",
                    options = listOf(Transcript.QuestionOption("alpha", "the first"), Transcript.QuestionOption("beta", "")),
                    multiple = true,
                    custom = true,
                ),
            ),
        )
        val rich = sessionView(
            messages = listOf(
                message("msg_u1", "user", listOf(textPart("p0", "msg_u1", "change the build file"))),
                message(
                    "msg_a1",
                    "assistant",
                    listOf(
                        textPart("p1", "msg_a1", "Sure - here is a **plan** with `code`:\n```bash\nls -la\n```"),
                        toolPart("prt1", "msg_a1", "bash", "completed", "ls -la", """{"command":"ls -la"}""", "total 8", """{"exit":0}"""),
                        toolPart("prt2", "msg_a1", "edit", "error", "src/Main.kt", """{"filePath":"src/Main.kt"}""", "", "", "no such file"),
                        Transcript.Part(
                            id = "prt3",
                            messageID = "msg_a1",
                            sessionID = SES,
                            type = "reasoning",
                            text = "thinking about the build file",
                            tool = "",
                            callID = "",
                            status = "",
                            input = "",
                            output = "",
                            title = "",
                        ),
                    ),
                ),
            ),
            busy = true,
            pending = listOf(prompt),
            questions = listOf(question),
        )
        renderChat(
            uiState(
                rich,
                busy = true,
                draft = "and now?",
                attachments = listOf(
                    OpenCodeApi.Attachment(filename = "notes.txt", mime = "text/plain", url = "file:///data/user/0/x/notes.txt", sizeBytes = 12L),
                ),
            ),
            availability = AgentAvailability.READY,
        )
        audit("chat")
        shot("17-chat-a11y.png")

        renderSessions(
            sessions = (1..3).map {
                OpenCodeApi.SessionInfo(id = "ses_a$it", title = "Conversation $it", directory = "/data/x", updatedAt = System.currentTimeMillis())
            },
            transcript = Transcript.Snapshot(
                listOf(
                    Transcript.SessionView(sessionID = "ses_a1", busy = true, messages = emptyList(), pending = emptyList()),
                    Transcript.SessionView(
                        sessionID = "ses_a2",
                        busy = false,
                        messages = emptyList(),
                        pending = listOf(prompt.copy(id = "per_a2", sessionID = "ses_a2")),
                    ),
                ),
            ),
            selected = "ses_a1",
        )
        audit("sessions")

        renderProjects(
            projects = listOf(
                Project(name = "gates", dir = File("/data/local/tmp/gates"), createdMs = 1L, lastOpenedMs = 2L),
            ),
            activeName = "gates",
        )
        audit("projects")

        renderWelcome(healthy)
        audit("welcome")
        renderWelcome(runtimeSummary("UNSUPPORTED_DEVICE", ready = false, detail = "armv7 is not supported"))
        audit("welcome-unsupported")

        renderSettings()
        audit("settings")
        shot("18-settings-a11y.png")

        val enoughControls = counts.values.sum() >= 25 && (counts["chat"] ?: 0) >= 10
        gate(
            "U7",
            violations.isEmpty() && enoughControls,
            "interactive=${counts} total=${counts.values.sum()} unnamed=${violations.size} " +
                (if (violations.isEmpty()) "" else violations.take(8).joinToString("; ")),
        )
    }

    // ---- U8: session switching ---------------------------------------------

    @Test
    fun u8_sessionListIsLazyAndSwitchingWorks() {
        val sessions = (1..40).map {
            OpenCodeApi.SessionInfo(
                id = "ses_$it",
                title = if (it == 7) "" else "Conversation $it",
                directory = "/data/local/tmp/gates",
                updatedAt = 1_700_000_000_000L + it * 1000L,
                revertMessageID = if (it == 9) "msg_x" else "",
            )
        }
        val busyView = Transcript.SessionView(sessionID = "ses_2", busy = true, messages = emptyList(), pending = emptyList())
        val waitingView = Transcript.SessionView(
            sessionID = "ses_3",
            busy = false,
            messages = emptyList(),
            pending = listOf(
                Transcript.Prompt(id = "per_s3", sessionID = "ses_3", permission = "bash", patterns = emptyList(), metadata = "{}"),
            ),
        )
        renderSessions(sessions, Transcript.Snapshot(listOf(busyView, waitingView)), selected = "ses_1")
        rule.waitForIdle()

        val firstRows = countPrefix("session_row_")
        val firstVisible = exists("session_row_ses_1")
        val lastNotComposed = !exists("session_row_ses_40")
        val busyLabel = onScreenText().contains(context.getString(R.string.sessions_busy))
        val waitingLabel = onScreenText().contains(context.getString(R.string.sessions_pending))
        // ses_7 has no title: the row must say so in words rather than render blank.
        val untitledLabel = onScreenText().contains(context.getString(R.string.sessions_untitled))
        val revertedNote = onScreenText().contains("msg_x")

        rule.onNodeWithTag("session_list").performScrollToIndex(39)
        rule.waitForIdle()
        val lastVisible = exists("session_row_ses_40")
        val firstGone = !exists("session_row_ses_1")
        val composedAfter = countPrefix("session_row_")

        rule.onNodeWithTag("session_row_ses_40").performClick()
        rule.waitForIdle()
        val switched = selectedSessions.contains("ses_40")

        rule.onNodeWithTag("session_delete_ses_38").performClick()
        rule.waitForIdle()
        val deleted = deletedSessions.contains("ses_38")

        rule.onNodeWithTag("session_rename_ses_37").performClick()
        rule.waitForIdle()
        val editor = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        if (editor) {
            rule.onNode(hasSetTextAction()).performTextClearance()
            rule.onNode(hasSetTextAction()).performTextInput("Renamed by gate")
            rule.onAllNodes(textMatcher(context.getString(R.string.sessions_rename_save)))[0].performClick()
            rule.waitForIdle()
        }
        val renamed = renamedSessions.contains("ses_37" to "Renamed by gate")

        rule.onNodeWithTag("session_new").performClick()
        rule.waitForIdle()
        val created = panelNew == 1

        // Empty state
        renderSessions(emptyList())
        val emptyTitle = onScreenText().contains(context.getString(R.string.sessions_empty_title))
        val emptyBody = onScreenText().contains(context.getString(R.string.sessions_empty_body))
        shot("19-sessions.png")

        val lazy = firstRows < 40 && composedAfter < 40
        val ok = firstVisible && lastNotComposed && lastVisible && firstGone && lazy && switched && deleted &&
            editor && renamed && created && busyLabel && waitingLabel && emptyTitle && emptyBody && untitledLabel &&
            revertedNote
        gate(
            "U8",
            ok,
            "rowsComposedBefore=$firstRows after=$composedAfter firstVisible=$firstVisible " +
                "lastLazy=$lastNotComposed scrolledToLast=$lastVisible firstUncomposed=$firstGone " +
                "switch=$switched delete=$deleted renameEditor=$editor rename=$renamed new=$created " +
                "busyPill=$busyLabel waitingPill=$waitingLabel untitled=$untitledLabel revertNote=$revertedNote " +
                "emptyState=$emptyTitle/$emptyBody",
        )
    }
}
