package ai.opencode.android.ui

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.client.WorkLog
import ai.opencode.android.projects.Project
import ai.opencode.android.runtime.RuntimeVersion
import ai.opencode.android.ui.changes.ChangesScreen
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.terminal.TerminalScreen
import ai.opencode.android.ui.files.FileNode
import ai.opencode.android.ui.files.FilesScreen
import ai.opencode.android.ui.files.OpenFile
import ai.opencode.android.ui.onboarding.WorkspaceOnboardingScreen
import ai.opencode.android.runtime.StorageMode
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
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

        /**
         * The project path the FILES fixture renders. The U9 gate asserts that the
         * screen shows exactly this string AND that "Copy path" hands it back, so
         * both sides must read one constant: the first version hard-coded the path
         * twice with different applicationIds (`.opencode` in the assertion,
         * `.opencode.debug` in the surface) and the gate failed on its own fixture
         * while the screen was correct.
         */
        const val FILES_FIXTURE_PATH =
            "/storage/emulated/0/Android/data/io.github.mcyber12.opencode/files/workspaces/gates"

        /**
         * The folder the v4 workspace step prints and the Settings workspace section
         * shows. One constant for the same reason FILES_FIXTURE_PATH is one: an
         * assertion that retypes a path is an assertion that will disagree with the
         * fixture one day.
         */
        const val WORKSPACE_FIXTURE_PATH = "/storage/emulated/0/Documents/OpenCode"
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
    private val openedDirs = mutableListOf<String>()
    private val openedFiles = mutableListOf<String>()
    private val savedCopies = mutableListOf<String>()
    private var upTaps = 0
    private var closedFiles = 0
    private var filesBack = 0
    private var allFilesRequests = 0
    private var workspacePicks = 0
    private var workspaceUse = 0
    private val workspacePath = mutableStateOf(WORKSPACE_FIXTURE_PATH)
    private val onboardingMessage = mutableStateOf("")
    private var starToggles = 0
    private var openChangesTaps = 0
    private var openTerminalTaps = 0
    private var changesBacks = 0
    private var terminalBacks = 0
    private val tabSelections = mutableListOf<ProjectTab>()

    /** (providerId, modelId, checked) the star callback reported, for U11's assertion. */
    private var starLastToggle: Triple<String, String, Boolean>? = null
    private var connectCalls = 0
    private var customProviderCalls = 0
    private var modelPicks = 0
    private val pickedModels = mutableListOf<String>()
    private var projectMoves = 0
    private val storageMessage = mutableStateOf("")
    private val storageMode = mutableStateOf(StorageMode.PUBLIC)
    private val storageVisible = mutableStateOf(true)
    private val storageCanGrant = mutableStateOf(false)
    private val storagePending = mutableStateOf(0)

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
        model: OpenCodeApi.ModelRef? = null,
        starred: List<OpenCodeApi.ModelRef> = emptyList(),
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
        model = model,
        starredModels = starred,
    )

    // ---- rendering ----------------------------------------------------------

    /**
     * Every screen a gate can show, and the state holder that drives it.
     *
     * `createComposeRule().setContent` may be called exactly once per test, and
     * run 34134527274 lost five of the eight chat gates to
     * `IllegalStateException: Cannot call setContent twice per test!` because each
     * render helper below composed its own screen. So the helpers no longer
     * compose: they write the fabricated fixture into snapshot state, say which
     * surface is showing, and let the single composition installed by
     * [composeOnce] recompose around it. A surface the gate is not looking at
     * leaves the tree exactly as it does when the user navigates away, which is
     * what the assertions expect - and growing [liveMessages] still recomposes the
     * transcript the way a streaming turn does.
     */
    private enum class Surface { CHAT, LIVE_CHAT, SESSIONS, PROJECTS, WELCOME, SETTINGS, FILES, ONBOARDING, CHANGES, TERMINAL }

    private val surface = mutableStateOf(Surface.CHAT)
    private val chatState = mutableStateOf(uiState())
    private val chatAvailability = mutableStateOf(AgentAvailability.READY)
    private val chatRuntime = mutableStateOf(healthy)
    private val liveMessages = mutableStateListOf<Transcript.Message>()
    private val liveBusy = mutableStateOf(false)
    private val sessionList = mutableStateListOf<OpenCodeApi.SessionInfo>()
    private val sessionTranscript = mutableStateOf(Transcript.Snapshot(emptyList()))
    private val sessionSelected = mutableStateOf("")
    private val projectList = mutableStateListOf<Project>()
    private val projectActive = mutableStateOf("")
    private val welcomeRuntime = mutableStateOf(healthy)
    private val filesNodes = mutableStateListOf<FileNode>()
    private val filesPath = mutableStateOf("")
    private val filesOpen = mutableStateOf<OpenFile?>(null)
    private val filesLoading = mutableStateOf(false)
    private val filesError = mutableStateOf("")
    private val settingsRuntime = mutableStateOf(healthy)
    private val settingsState = mutableStateOf(uiState())
    private val settingsAvailability = mutableStateOf(AgentAvailability.READY)
    private val changesTurns = mutableStateOf(emptyList<WorkLog.TurnChanges>())
    private val terminalCommands = mutableStateOf(emptyList<WorkLog.Command>())
    private var composed = false

    /** The one and only `setContent` of a test method. */
    private fun composeOnce() {
        if (composed) return
        composed = true
        rule.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                when (surface.value) {
                    Surface.CHAT -> ChatSurface()
                    Surface.LIVE_CHAT -> LiveChatSurface()
                    Surface.SESSIONS -> SessionsSurface()
                    Surface.PROJECTS -> ProjectsSurface()
                    Surface.WELCOME -> WelcomeSurface()
                    Surface.SETTINGS -> SettingsSurface()
                    Surface.FILES -> FilesSurface()
                    Surface.ONBOARDING -> OnboardingSurface()
                    Surface.CHANGES -> ChangesSurface()
                    Surface.TERMINAL -> TerminalSurface()
                }
            }
        }
    }

    /** Publish the fixture written by a render helper, then let composition catch up. */
    private fun show() {
        composeOnce()
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
    }

    @Composable
    private fun ChatSurface() {
        ChatScreen(
            state = chatState.value,
            runtime = chatRuntime.value,
            availability = chatAvailability.value,
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
            onOpenChanges = { openChangesTaps++ },
            onOpenTerminal = { openTerminalTaps++ },
            onPickModel = { providerId, modelId ->
                modelPicks++
                pickedModels.add("$providerId/$modelId")
            },
            onPermissionReply = { id, response -> replies.add(id to response) },
            onQuestionSubmit = { id, answers -> questionAnswers.add(id to answers) },
            onQuestionSkip = { questionSkips.add(it) },
            onAttach = { attaches++ },
            onRemoveAttachment = { removedAttachments.add(it) },
            onDismissBanner = { dismissals++ },
        )
    }

    /** Same screen, but the message list is observable so a gate can grow it. */
    @Composable
    private fun LiveChatSurface() {
        ChatScreen(
            state = uiState(
                sessionView(messages = liveMessages.toList(), busy = liveBusy.value),
                busy = liveBusy.value,
            ),
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
            onPickModel = { providerId, modelId ->
                modelPicks++
                pickedModels.add("$providerId/$modelId")
            },
            onPermissionReply = { id, response -> replies.add(id to response) },
            onQuestionSubmit = { id, answers -> questionAnswers.add(id to answers) },
            onQuestionSkip = { questionSkips.add(it) },
            onAttach = { attaches++ },
            onRemoveAttachment = { removedAttachments.add(it) },
            onDismissBanner = { dismissals++ },
        )
    }

    @Composable
    private fun SessionsSurface() {
        SessionPanel(
            sessions = sessionList.toList(),
            selectedSession = sessionSelected.value,
            transcript = sessionTranscript.value,
            onBack = { panelBack++ },
            onSelect = { selectedSessions.add(it) },
            onNew = { panelNew++ },
            onDelete = { deletedSessions.add(it) },
            onRename = { id, title -> renamedSessions.add(id to title) },
        )
    }

    @Composable
    private fun ProjectsSurface() {
        ProjectsScreen(
            projects = projectList.toList(),
            activeName = projectActive.value,
            runtimeLine = context.getString(R.string.welcome_stage_healthy_title),
            onCreate = { createdProjects.add(it) },
            onOpen = { openedProjectList.add(it) },
            onBack = { panelBack++ },
        )
    }

    @Composable
    private fun WelcomeSurface() {
        WelcomeScreen(
            runtime = welcomeRuntime.value,
            onContinue = { welcomeContinue++ },
            onOpenSettings = { welcomeSettings++ },
        )
    }

    @Composable
    private fun OnboardingSurface() {
        WorkspaceOnboardingScreen(
            folderPath = workspacePath.value,
            visibleToFileManagers = storageVisible.value,
            message = onboardingMessage.value,
            onPickFolder = { workspacePicks++ },
            onUseFolder = { workspaceUse++ },
        )
    }

    @Composable
    private fun FilesSurface() {
        FilesScreen(
            projectName = "gates",
            projectPath = FILES_FIXTURE_PATH,
            storageMode = storageMode.value,
            storageVisibleToFileManagers = storageVisible.value,
            storageAppFolderBrowsableByFileManagers = storageVisible.value,
            storageCanGrantAllFilesAccess = storageCanGrant.value,
            storagePendingMove = storagePending.value,
            storageMessage = storageMessage.value,
            currentPath = filesPath.value,
            nodes = filesNodes.toList(),
            loading = filesLoading.value,
            error = filesError.value,
            openFile = filesOpen.value,
            onOpenDir = { openedDirs.add(it) },
            onOpenFile = { openedFiles.add(it) },
            onUp = { upTaps++ },
            onCloseFile = { closedFiles++ },
            onSaveCopy = { savedCopies.add(it) },
            onRequestAllFilesAccess = { allFilesRequests++ },
            onMoveProjects = { projectMoves++ },
            onBack = { filesBack++ },
        )
    }

    private fun renderFiles(
        path: String = "",
        nodes: List<FileNode> = emptyList(),
        open: OpenFile? = null,
        loading: Boolean = false,
        error: String = "",
    ) {
        filesPath.value = path
        filesNodes.clear()
        filesNodes.addAll(nodes)
        filesOpen.value = open
        filesLoading.value = loading
        filesError.value = error
        surface.value = Surface.FILES
        show()
    }

    @Composable
    private fun SettingsSurface() {
        SettingsScreen(
            runtime = settingsRuntime.value,
            state = settingsState.value,
            availability = settingsAvailability.value,
            diagnosticsLines = listOf("HEALTHY", "spawn ok", "bind 127.0.0.1 only"),
            diagnosticsLoading = false,
            storedProviderIds = "anthropic",
            hardwareBacked = "software",
            appVersion = "1.18.23-phase9 (7)",
            theme = ThemeChoice.DARK,
            dynamicColor = false,
            onThemeChange = { },
            onDynamicColorChange = { },
            onSetModel = { _, _ -> },
            onClearModel = { },
            onToggleStar = { providerId, modelId, checked ->
                starToggles++
                starLastToggle = Triple(providerId, modelId, checked)
            },
            onConnectProvider = { _, _ -> connectCalls++ },
            onAddCustomProvider = { _, _, _, _, _ -> customProviderCalls++ },
            workspacePath = WORKSPACE_FIXTURE_PATH,
            workspaceVisibleToFileManagers = storageVisible.value,
            workspaceCanGrantAllFilesAccess = storageCanGrant.value,
            workspacePendingMove = storagePending.value,
            onPickWorkspace = { workspacePicks++ },
            onGrantAllFilesAccess = { allFilesRequests++ },
            onMoveWorkspaceProjects = { projectMoves++ },
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

    private fun renderChat(
        state: OpenCodeRepository.UiState,
        availability: AgentAvailability,
        runtime: RuntimeSummary = healthy,
    ) {
        chatState.value = state
        chatAvailability.value = availability
        chatRuntime.value = runtime
        surface.value = Surface.CHAT
        show()
    }

    private fun renderLiveChat(
        messages: List<Transcript.Message>,
        busy: Boolean,
    ): Pair<SnapshotStateList<Transcript.Message>, MutableState<Boolean>> {
        liveMessages.clear()
        liveMessages.addAll(messages)
        liveBusy.value = busy
        surface.value = Surface.LIVE_CHAT
        show()
        return liveMessages to liveBusy
    }

    private fun renderSessions(
        sessions: List<OpenCodeApi.SessionInfo>,
        transcript: Transcript.Snapshot = Transcript.Snapshot(emptyList()),
        selected: String = "",
    ) {
        sessionList.clear()
        sessionList.addAll(sessions)
        sessionTranscript.value = transcript
        sessionSelected.value = selected
        surface.value = Surface.SESSIONS
        show()
    }

    private fun renderProjects(projects: List<Project>, activeName: String = "") {
        projectList.clear()
        projectList.addAll(projects)
        projectActive.value = activeName
        surface.value = Surface.PROJECTS
        show()
    }

    private fun renderWelcome(runtime: RuntimeSummary) {
        welcomeRuntime.value = runtime
        surface.value = Surface.WELCOME
        show()
    }

    private fun renderSettings(
        runtime: RuntimeSummary = healthy,
        state: OpenCodeRepository.UiState = uiState(),
        availability: AgentAvailability = AgentAvailability.READY,
    ) {
        settingsRuntime.value = runtime
        settingsState.value = state
        settingsAvailability.value = availability
        surface.value = Surface.SETTINGS
        show()
    }

    @Composable
    private fun ChangesSurface() {
        ChangesScreen(
            projectName = "gates",
            turns = changesTurns.value,
            onBack = { changesBacks++ },
            onSelectTab = { tabSelections.add(it) },
        )
    }

    @Composable
    private fun TerminalSurface() {
        TerminalScreen(
            projectName = "gates",
            commands = terminalCommands.value,
            onBack = { terminalBacks++ },
            onSelectTab = { tabSelections.add(it) },
            runtimeLog = listOf("runtime line one", "runtime line two"),
        )
    }

    private fun renderChanges(turns: List<WorkLog.TurnChanges>) {
        changesTurns.value = turns
        surface.value = Surface.CHANGES
        show()
    }

    private fun renderTerminal(commands: List<WorkLog.Command>) {
        terminalCommands.value = commands
        surface.value = Surface.TERMINAL
        show()
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
        return nodes.isNotEmpty() && !nodes.first().config.contains(SemanticsProperties.Disabled)
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
        // The shared emitter, so the verdict also lands in the on-device file the
        // harness reads back: logcat alone truncated every detail in run #6.
        printGate(id, ok, detail)
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
        renderChat(
            uiState(sessionView(messages = listOf(message("msg_u1", "user", listOf(textPart("p", "msg_u1", "list the project"))), assistant, failedTurn))),
            availability = AgentAvailability.READY,
        )
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

        // A failed tool starts expanded: a failure is never one tap deeper than a
        // success. It lives in the LAST message, and U1 proves this list only
        // composes its viewport, so scroll it into view before asserting on it -
        // run #6 scored failedCardOpen=false purely because the card was not
        // composed yet, not because the product kept a failure one tap deeper.
        runCatching {
            rule.onNodeWithTag(TAG_TRANSCRIPT)
                .performScrollToNode(hasTestTag("$TAG_TOOL_HEADER" + "_prt_fail"))
        }.onFailure {
            // The fixture holds exactly three messages, so the failed card is item 2.
            rule.onNodeWithTag(TAG_TRANSCRIPT).performScrollToIndex(2)
        }
        rule.waitForIdle()
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
        // Run #8's answersSeen=[] said the callback never fired at all, while
        // 12-chat-asks.png showed the radio selected and the button enabled. So
        // record whether the button was actually clickable at tap time, and tap
        // again (as a user would) if the first tap was lost to a race - without
        // ever hiding what happened: both facts land in the gate detail.
        // Phase 7 moves the asks into a bottom sheet whose content column is
        // verticalScroll: the submit row can sit below the sheet's fold, and
        // performClick does not scroll (it injects the tap at the node's bounds,
        // which would be outside the sheet). Scroll the row into view first,
        // exactly as a thumb would - and if the scroll action is not exposed,
        // keep going so the tap itself reports what happened.
        val scrolledIntoView = runCatching {
            rule.onNodeWithTag(TAG_QUESTION_SUBMIT).performScrollTo()
        }.isSuccess
        rule.waitForIdle()
        val submitNodes = rule.onAllNodesWithTag(TAG_QUESTION_SUBMIT).fetchSemanticsNodes()
        val submitEnabled = submitNodes.isNotEmpty() &&
            !submitNodes.first().config.contains(SemanticsProperties.Disabled)
        var submitClicks = 0
        repeat(3) {
            if (questionAnswers.isNotEmpty()) return@repeat
            val n = rule.onAllNodesWithTag(TAG_QUESTION_SUBMIT).fetchSemanticsNodes()
            if (n.isEmpty() || n.first().config.contains(SemanticsProperties.Disabled)) return@repeat
            rule.onNodeWithTag(TAG_QUESTION_SUBMIT).performClick()
            submitClicks++
            rule.waitForIdle()
        }
        val answered = questionAnswers.any { it.first == "que_gate1" && it.second.any { a -> a.contains("staging") } }

        // Skip is the other honest answer.
        renderChat(
            uiState(sessionView(messages = emptyList(), busy = true, questions = listOf(question)), busy = true),
            availability = AgentAvailability.READY,
        )
        runCatching { rule.onNodeWithTag(TAG_QUESTION_SKIP).performScrollTo() }
        rule.waitForIdle()
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
                "answered=$answered skipped=$skipped answersSeen=$questionAnswers " +
                "submitEnabledAtTap=$submitEnabled submitClicks=$submitClicks scrolledIntoView=$scrolledIntoView",
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

        // Phase 10 continuation: the project-files screen is part of the app now,
        // so it is part of the semantics audit as well (a directory row, a file
        // row and the two actions that get files out to a visible folder).
        renderFiles(
            path = "src",
            nodes = listOf(
                FileNode(name = "main.kt", path = "src/main.kt", isDirectory = false),
                FileNode(name = "app", path = "src/app", isDirectory = true),
            ),
        )
        audit("files")
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

        // ses_9 carries the revert note and sits at index 8: with a real lazy list
        // (rowsComposedBefore=7) it is composed neither in the first viewport nor
        // after scrolling to the END - run #8 proved the second half by still
        // reading revertNote=false after performScrollToIndex(39) had scrolled it
        // back out. Scroll the row itself into the viewport, read, then go on.
        rule.onNodeWithTag("session_list").performScrollToIndex(8)
        rule.waitForIdle()
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

    // ---- U9: the project-files screen (Phase 10 continuation) ---------------

    /**
     * The file browser is how a user sees what the agent wrote without leaving the
     * app, so the gate drives it the way a user does: a listing renders, a folder
     * opens, a file opens with its text, the location line shows the real path, and
     * the two ways out to visible storage ("Save a copy", "Publish to a folder")
     * are present and wired.
     *
     * This is a UI gate, not a storage gate: whether the files are reachable from
     * outside the app is proven by the W4/visibility gates on a device, and whether
     * the agent's OWN file layer lists them is proven by the Phase 7 W2 gate.
     */
    @Test
    fun u9_fileBrowserListsOpensAndOffersAWayOut() {
        val rootPath = FILES_FIXTURE_PATH
        renderFiles(
            path = "",
            nodes = listOf(
                FileNode(name = "src", path = "src", isDirectory = true),
                FileNode(name = "README.md", path = "README.md", isDirectory = false),
            ),
        )
        val listed = exists("files_list") && countTag("files_dir") == 1 && countTag("files_file") == 1
        val pathShown = onScreenText().contains(rootPath)
        val locationCopy = onScreenText().contains(context.getString(R.string.files_location_title))
        shot("20-files-listing.png")

        // tapping the folder asks for that folder (the driver loads it)
        rule.onAllNodesWithTag("files_dir")[0].performClick()
        rule.waitForIdle()
        val openedDir = openedDirs.contains("src")

        // ...and inside a folder the "up one level" affordance exists
        renderFiles(path = "src", nodes = listOf(FileNode(name = "main.kt", path = "src/main.kt", isDirectory = false)))
        val upShown = exists("files_up")
        rule.onAllNodesWithTag("files_up")[0].performClick()
        rule.waitForIdle()
        val upWorks = upTaps == 1

        // tapping a file opens it; the viewer shows the content and the two actions
        rule.onAllNodesWithTag("files_file")[0].performClick()
        rule.waitForIdle()
        val openedFile = openedFiles.contains("src/main.kt")

        renderFiles(
            path = "src",
            open = OpenFile(
                path = "src/main.kt",
                name = "main.kt",
                text = "fun main() = println(\"hello\")",
                bytes = 29,
                binary = false,
                truncated = false,
            ),
        )
        val viewer = exists("files_viewer") && exists("files_viewer_body")
        val bodyText = onScreenText().contains("fun main() = println(\"hello\")")
        val saveCopyShown = exists("files_save_copy")
        rule.onAllNodesWithTag("files_save_copy")[0].performClick()
        rule.waitForIdle()
        val saveCopyWired = savedCopies.contains("src/main.kt")
        shot("21-files-viewer.png")

        // closing returns to the listing
        renderFiles(path = "src", nodes = listOf(FileNode(name = "main.kt", path = "src/main.kt", isDirectory = false)))
        rule.onAllNodesWithTag("files_file")[0].performClick()
        rule.waitForIdle()
        renderFiles(path = "src", open = OpenFile("src/main.kt", "main.kt", "x", 1L, false, false))
        rule.onAllNodesWithTag("files_viewer_close")[0].performClick()
        rule.waitForIdle()
        val closed = closedFiles == 1

        // ---- v4 items 3 and 4: what the storage screen is NOT --------------------
        // Every control the brief removed must be gone, not merely hidden: "Copy
        // path" and "Export a copy" (the workspace folder is a normal folder a file
        // manager opens since v3, and the export existed to work around a location
        // the user could not reach), and the "use a folder I choose" / "use the
        // default location" pair (there is one workspace and it is chosen in
        // Settings, or on the first-run step).
        renderFiles(path = "", nodes = emptyList())
        val noCopyPath = !exists("files_copy_path")
        val noExport = !exists("files_publish")
        val noChoose = !exists("files_storage_choose")
        val noReset = !exists("files_storage_default")

        // the empty state is a real state, not a blank screen
        renderFiles(path = "", nodes = emptyList())
        val emptyShown = exists("files_empty")

        // ---- storage panel (Phase 10 continuation v3) --------------------------
        // In the good case the panel names the real location and does not nag about a
        // permission the user does not need.
        renderFiles(path = "", nodes = emptyList())
        val goodPanelVisible = exists("files_storage_mode") && onScreenText()
            .contains(context.getString(R.string.files_storage_mode_public))
        val goodExplanation = onScreenText().contains(context.getString(R.string.files_location_public))
        val goodNoGrant = !exists("files_storage_grant")

        // In the fallback case the panel says what is wrong, offers the one repair
        // that only this screen offers (the grant), and reports pending projects
        // with the move action.
        storageMode.value = StorageMode.APP_EXTERNAL
        storageVisible.value = false
        storageCanGrant.value = true
        storagePending.value = 3
        rule.waitForIdle()
        val fallbackVisible = onScreenText().contains(context.getString(R.string.files_storage_mode_app_external))
        val fallbackExplained = onScreenText().contains(context.getString(R.string.files_location_app_external))
        val pendingShown = exists("files_storage_pending") &&
            onScreenText().contains(context.getString(R.string.files_storage_pending, 3))
        val grantShown = exists("files_storage_grant")
        rule.onAllNodesWithTag("files_storage_grant")[0].performClick()
        rule.waitForIdle()
        val grantWired = allFilesRequests == 1
        rule.onAllNodesWithTag("files_storage_move")[0].performClick()
        rule.waitForIdle()
        val moveWired = projectMoves == 1
        shot("22-files-storage-panel.png")

        // With a chosen folder in effect the panel says so and still offers no
        // way to change it from here: the workspace switch lives in Settings.
        storageMode.value = StorageMode.CHOSEN
        storageVisible.value = true
        storageCanGrant.value = false
        storagePending.value = 0
        storageMessage.value = "moved 2"
        rule.waitForIdle()
        val chosenVisible = onScreenText().contains(context.getString(R.string.files_storage_mode_chosen))
        val messageShown = onScreenText().contains("moved 2")
        val stillNoChoose = !exists("files_storage_choose")
        // back to the default state so the remaining assertions see a clean panel
        storageMode.value = StorageMode.PUBLIC
        storageMessage.value = ""
        storageCanGrant.value = false
        renderFiles(path = "", nodes = emptyList())

        val ok = listed && pathShown && locationCopy && openedDir && upShown && upWorks && openedFile &&
            viewer && bodyText && saveCopyShown && saveCopyWired && closed && emptyShown &&
            noCopyPath && noExport && noChoose && noReset &&
            goodPanelVisible && goodExplanation && goodNoGrant &&
            fallbackVisible && fallbackExplained && pendingShown && grantShown && grantWired &&
            moveWired && chosenVisible && messageShown && stillNoChoose
        gate(
            "U9",
            ok,
            "listed=$listed pathShown=$pathShown locationCopy=$locationCopy openedDir=$openedDir " +
                "upShown=$upShown upWorks=$upWorks openedFile=$openedFile viewer=$viewer body=$bodyText " +
                "saveCopy=$saveCopyShown/$saveCopyWired closed=$closed empty=$emptyShown " +
                "v4Removed=copyPath:$noCopyPath,export:$noExport,choose:$noChoose,reset:$noReset " +
                "panel=$goodPanelVisible/$goodExplanation/$goodNoGrant " +
                "fallback=$fallbackVisible/$fallbackExplained/$pendingShown/$grantShown/$grantWired/" +
                "$moveWired chosen=$chosenVisible/$messageShown/$stillNoChoose",
        )
    }

    // ---- U10: the model quick switch (v4 item 3) -----------------------------

    @Test
    fun u10_quickSwitchOffersOnlyStarredModelsAndPicksInPlace() {
        val starred = listOf(
            OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini"),
            OpenCodeApi.ModelRef("google", "gemini-2.5-flash"),
        )
        renderChat(
            uiState(model = starred[0], starred = starred),
            AgentAvailability.READY,
        )
        val shown = exists("model_quick_switch")
        val labelNamesCurrent = onScreenText().contains("openrouter/openai/gpt-4o-mini")

        // one tap opens the list: exactly the starred models, no catalog - and the
        // model id keeps its own slash (openai/gpt-4o-mini is a MODEL id)
        rule.onAllNodesWithTag("model_quick_switch")[0].performClick()
        rule.waitForIdle()
        val menuOpen = exists("model_quick_switch_menu")
        val listed = countPrefix("model_pick_") == starred.size
        val idsShown = onScreenText().contains("openai/gpt-4o-mini") &&
            onScreenText().contains("gemini-2.5-flash")
        shot("23-model-quick-switch.png")

        // picking one calls back with the two halves, and does NOT require Settings
        rule.onAllNodesWithTag("model_pick_1")[0].performClick()
        rule.waitForIdle()
        val picked = modelPicks == 1 && pickedModels.contains("google/gemini-2.5-flash")
        val noSettingsTrip = openSettings == 0

        // with nothing starred the menu says what to do and offers the one place
        // where models are starred
        renderChat(uiState(model = starred[0], starred = emptyList()), AgentAvailability.READY)
        rule.onAllNodesWithTag("model_quick_switch")[0].performClick()
        rule.waitForIdle()
        val emptyExplained = exists("model_quick_switch_empty") &&
            onScreenText().contains(context.getString(R.string.chat_model_empty))
        rule.onAllNodesWithTag("model_quick_switch_settings")[0].performClick()
        rule.waitForIdle()
        val settingsOffered = openSettings == 1

        val settled = modelPicks == 1 && pickedModels.size == 1
        gate(
            "U10",
            shown && labelNamesCurrent && menuOpen && listed && idsShown && picked && noSettingsTrip &&
                emptyExplained && settingsOffered && settled,
            "shown=$shown label=$labelNamesCurrent menu=$menuOpen listed=$listed/$idsShown " +
                "picked=$picked/$pickedModels settingsTrip=$noSettingsTrip " +
                "emptyExplained=$emptyExplained offered=$settingsOffered",
        )
    }

    // ---- U11: provider search and the two activation paths (v4 item 2) --------

    @Test
    fun u11_providerSearchActivatesWithAKeyOnlyAndKeepsTheManualPath() {
        val providers = OpenCodeApi.ProviderSnapshot(
            allIds = listOf("openrouter", "openai", "opencode", "anthropic"),
            connected = listOf("opencode"),
            defaultModel = mapOf(
                "openrouter" to "openai/gpt-4o-mini",
                "openai" to "gpt-4o",
                "opencode" to "big-pickle",
                "anthropic" to "claude-sonnet-4",
            ),
            entries = listOf(
                OpenCodeApi.ProviderEntry("openrouter", "OpenRouter", listOf(OpenCodeApi.ModelEntry("openai/gpt-4o-mini", "GPT-4o mini", "" ))),
                OpenCodeApi.ProviderEntry("openai", "OpenAI", listOf(OpenCodeApi.ModelEntry("gpt-4o", "GPT-4o", "" ))),
                OpenCodeApi.ProviderEntry("opencode", "OpenCode", listOf(OpenCodeApi.ModelEntry("big-pickle", "Big Pickle", "" ))),
                OpenCodeApi.ProviderEntry("anthropic", "Anthropic", listOf(OpenCodeApi.ModelEntry("claude-sonnet-4", "Claude Sonnet 4", "" ))),
            ),
        )
        renderSettings(
            state = uiState(
                model = OpenCodeApi.ModelRef("opencode", "big-pickle"),
                starred = listOf(OpenCodeApi.ModelRef("openrouter", "openai/gpt-4o-mini")),
            ),
        )
        settingsState.value = settingsState.value.copy(providers = providers)
        rule.waitForIdle()

        rule.onAllNodesWithTag("settings_list")[0].performScrollToNode(hasTestTag("provider_search"))
        rule.waitForIdle()
        val searchShown = exists("provider_search")

        // search narrows the catalog: "openr" leaves one of the four providers
        rule.onAllNodesWithTag("provider_search")[0].performTextInput("openr")
        rule.waitForIdle()
        // the two providers that do not match are gone from the list, not just
        // scrolled away - that is the whole point of the search
        val filtered = exists("provider_openrouter") && !exists("provider_openai") &&
            !exists("provider_anthropic")
        val countLine = onScreenText().contains(context.getString(R.string.settings_providers_shown, 1, 4))
        shot("24-provider-search.png")

        // tapping the connect action on a listed provider asks for the key only
        rule.onAllNodesWithTag("provider_connect_openrouter")[0].performScrollTo()
        rule.waitForIdle()
        rule.onAllNodesWithTag("provider_connect_openrouter")[0].performClick()
        rule.waitForIdle()
        val dialogShown = exists("provider_key_value") && exists("provider_key_save")
        val onlyKeyAsked = onScreenText()
            .contains(context.getString(R.string.settings_provider_connect_body))
        rule.onAllNodesWithTag("provider_key_value")[0].performTextInput("sk-gate")
        rule.waitForIdle()
        rule.onAllNodesWithTag("provider_key_save")[0].performClick()
        rule.waitForIdle()
        val activated = connectCalls == 1

        // starring: the checkbox reflects the starred list and reports the toggle.
        // The models (and therefore the stars) are behind the provider row's expansion -
        // a collapsed row is what a user sees first, so what this gate proves is the
        // flow: open the provider, see the star, toggle it.
        val starTag = "model_star_openrouter_openai/gpt-4o-mini"
        val starHiddenUntilExpanded = !exists(starTag)
        rule.onAllNodesWithTag("provider_openrouter")[0].performClick()
        rule.waitForIdle()
        val starShown = exists(starTag)
        val starChecked = starShown &&
            rule.onAllNodesWithTag(starTag)[0].fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        if (starShown) {
            rule.onAllNodesWithTag(starTag)[0].performScrollTo()
            rule.waitForIdle()
            rule.onAllNodesWithTag(starTag)[0].performClick()
            rule.waitForIdle()
        }
        val starWired = starToggles == 1
        val starReported = starLastToggle == Triple("openrouter", "openai/gpt-4o-mini", false)

        // the manual path for a provider no catalog knows: four fields plus the key
        rule.onAllNodesWithTag("settings_list")[0].performScrollToNode(hasTestTag("custom_provider_id"))
        rule.waitForIdle()
        val customShown = exists("custom_provider_id") && exists("custom_provider_baseurl") &&
            exists("custom_provider_models") && exists("custom_provider_save")
        rule.onAllNodesWithTag("custom_provider_id")[0].performTextInput("9router")
        rule.onAllNodesWithTag("custom_provider_baseurl")[0].performTextInput("http://192.168.1.10:20128/v1")
        rule.onAllNodesWithTag("custom_provider_models")[0].performTextInput("my-model")
        rule.onAllNodesWithTag("custom_provider_key")[0].performTextInput("sk-custom")
        rule.waitForIdle()
        rule.onAllNodesWithTag("custom_provider_save")[0].performScrollTo()
        rule.waitForIdle()
        rule.onAllNodesWithTag("custom_provider_save")[0].performClick()
        rule.waitForIdle()
        val customSaved = customProviderCalls == 1
        shot("25-custom-provider.png")

        gate(
            "U11",
            searchShown && filtered && countLine && dialogShown && onlyKeyAsked && activated &&
                starHiddenUntilExpanded && starShown && starChecked && starWired && starReported &&
                customShown && customSaved,
            "search=$searchShown/$filtered/$countLine keyOnly=$dialogShown/$onlyKeyAsked " +
                "activated=$activated star=$starShown/$starChecked/$starWired/$starReported " +
                "collapsedFirst=$starHiddenUntilExpanded custom=$customShown/$customSaved " +
                "reported=$starLastToggle",
        )
    }

    // ---- U12: the workspace step and the Settings switch (v4 items 1 and 4) ---

    @Test
    fun u12_workspaceStepHasOneFolderAndOneActionAndSettingsOwnsTheSwitch() {
        workspacePath.value = WORKSPACE_FIXTURE_PATH
        onboardingMessage.value = ""
        surface.value = Surface.ONBOARDING
        show()

        val screenShown = exists("onboarding_workspace")
        val pathShown = onScreenText().contains(WORKSPACE_FIXTURE_PATH)
        val pickerShown = exists("onboarding_workspace_pick")
        val oneAction = exists("onboarding_workspace_use")
        // the controls the brief removed must not exist on this screen either
        val noCopyPath = !exists("files_copy_path") && !exists("files_publish")
        val noDefaultPair = !exists("files_storage_choose") && !exists("files_storage_default")
        shot("26-workspace-onboarding.png")

        rule.onAllNodesWithTag("onboarding_workspace_pick")[0].performClick()
        rule.waitForIdle()
        val pickWired = workspacePicks == 1
        rule.onAllNodesWithTag("onboarding_workspace_use")[0].performClick()
        rule.waitForIdle()
        val useWired = workspaceUse == 1

        // a refusal is reported on the screen that asked
        onboardingMessage.value = context.getString(
            R.string.onboarding_workspace_unusable,
            WORKSPACE_FIXTURE_PATH,
        )
        rule.waitForIdle()
        val messageShown = exists("onboarding_workspace_message") &&
            onScreenText().contains(onboardingMessage.value)

        // the fallback location says so, and the note is not a second action
        storageVisible.value = false
        rule.waitForIdle()
        val hiddenExplained = exists("onboarding_workspace_hidden")
        val stillTwoControls = countTag("onboarding_workspace_pick") == 1 &&
            countTag("onboarding_workspace_use") == 1
        storageVisible.value = true

        // Settings carries the switch: the picker, the repair for a lost grant, the
        // move for projects left behind, and the honest note about what switching hides
        renderSettings(state = uiState())
        storageCanGrant.value = true
        storagePending.value = 2
        rule.waitForIdle()
        rule.onAllNodesWithTag("settings_list")[0].performScrollToNode(hasTestTag("settings_workspace_pick"))
        rule.waitForIdle()
        val settingsHasFolder = onScreenText().contains(WORKSPACE_FIXTURE_PATH)
        val settingsPicker = exists("settings_workspace_pick")
        val settingsGrant = exists("settings_workspace_grant")
        val settingsMove = exists("settings_workspace_move")
        val switchNote = onScreenText().contains(context.getString(R.string.settings_workspace_switch_note))
        shot("27-settings-workspace.png")
        storageCanGrant.value = false
        storagePending.value = 0

        gate(
            "U12",
            screenShown && pathShown && pickerShown && oneAction && noCopyPath && noDefaultPair &&
                pickWired && useWired && messageShown && hiddenExplained && stillTwoControls &&
                settingsHasFolder && settingsPicker && settingsGrant && settingsMove && switchNote,
            "onboarding=$screenShown/$pathShown/$pickerShown/$oneAction " +
                "v4Removed=$noCopyPath/$noDefaultPair wired=$pickWired/$useWired " +
                "message=$messageShown hidden=$hiddenExplained controls=$stillTwoControls " +
                "settings=$settingsHasFolder/$settingsPicker/$settingsGrant/$settingsMove note=$switchNote",
        )
    }

    // ---- U13: the Changes surface (v7 redesign) ------------------------------

    @Test
    fun u13_changesSurface_tabsStagesDiffsAndEmptyState() {
        // (a) the tab strip on the chat, and that the two new tabs are wired.
        renderChat(uiState(sessionView()), AgentAvailability.READY)
        val tabsShown = exists("project_tab_chat") && exists("project_tab_files") &&
            exists("project_tab_changes") && exists("project_tab_terminal")
        val statusChip = exists("chat_status_pill")
        rule.onNodeWithTag("project_tab_changes").performClick()
        rule.onNodeWithTag("project_tab_terminal").performClick()
        rule.waitForIdle()
        val tabsWired = openChangesTaps == 1 && openTerminalTaps == 1

        // (b) two stages out of three turns: the shell-only turn must not appear,
        // and the newest stage leads.
        val editMeta = "{\"filediff\":{\"file\":\"src/App.kt\",\"patch\":" +
            "\"--- a/src/App.kt\\n+++ b/src/App.kt\\n+val redesign = true\",\"additions\":3,\"deletions\":1}}"
        val view = sessionView(
            messages = listOf(
                message("m1", "user", listOf(textPart("p_q", "m1", "please change it"))),
                message(
                    "m2", "assistant",
                    listOf(
                        toolPart("p_edit", "m2", "edit", "completed", "src/App.kt", "{\"filePath\":\"src/App.kt\"}", "", metadata = editMeta),
                        toolPart("p_ls", "m2", "bash", "completed", "ls", "{\"command\":\"ls\"}", "ok"),
                    ),
                ),
                message(
                    "m3", "assistant",
                    listOf(toolPart("p_write", "m3", "write", "completed", "notes.md", "{\"filePath\":\"notes.md\"}", "")),
                ),
            ),
        )
        val turns = WorkLog.changes(view)
        renderChanges(turns)
        val screenShown = exists("changes_screen")
        val bothStages = exists("changes_turn_m2") && exists("changes_turn_m3")
        val newestFirst = turns.size == 2 && turns[0].messageID == "m3"
        val rowCount = countPrefix("changes_row_")
        val counts = countText(context.getString(R.string.changes_additions, 3)) > 0 &&
            countText(context.getString(R.string.changes_deletions, 1)) > 0

        // (c) the patch is one tap away, and hidden until that tap.
        val patchHiddenBefore = countText("val redesign = true") == 0
        rule.onNodeWithTag("changes_row_p_edit").performClick()
        rule.waitForIdle()
        val patchShown = countText("val redesign = true") > 0
        shot("28-changes-stages.png")

        // (d) a session with no file changes says so instead of showing nothing.
        renderChanges(emptyList())
        val emptyShown = countText(context.getString(R.string.changes_empty_title)) > 0

        gate(
            "U13",
            tabsShown && statusChip && tabsWired && screenShown && bothStages && newestFirst &&
                rowCount == 2 && counts && patchHiddenBefore && patchShown && emptyShown,
            "tabs=$tabsShown chip=$statusChip wired=$tabsWired screen=$screenShown stages=$bothStages " +
                "newestFirst=$newestFirst rows=$rowCount counts=$counts " +
                "patch=hidden:$patchHiddenBefore,shown:$patchShown empty=$emptyShown",
        )
    }

    // ---- U14: the Terminal surface (v7 redesign) -----------------------------

    @Test
    fun u14_terminalSurface_commandsExitCodesLogAndEmptyState() {
        val okMeta = "{\"output\":\"total 4\\nBUILD OK\",\"exit\":0}"
        val view = sessionView(
            messages = listOf(
                message(
                    "m1", "assistant",
                    listOf(
                        toolPart("p_ok", "m1", "bash", "completed", "gradle build", "{\"command\":\"gradle build\",\"description\":\"Build the app\"}", "", metadata = okMeta),
                        toolPart("p_edit", "m1", "edit", "completed", "a.kt", "{\"filePath\":\"a.kt\"}", ""),
                    ),
                ),
                message(
                    "m2", "assistant",
                    listOf(
                        toolPart("p_run", "m2", "bash", "running", "sleep", "{\"command\":\"sleep 60\"}", ""),
                        toolPart("p_bad", "m2", "bash", "error", "false", "{\"command\":\"exit 1\"}", "", error = "boom"),
                    ),
                ),
            ),
        )
        val commands = WorkLog.commands(view)
        renderTerminal(commands)
        val screenShown = exists("terminal_screen")
        val rows = countPrefix("terminal_row_")
        val commandShown = countText("gradle build") > 0
        val outputShown = countText("BUILD OK") > 0
        val exitShown = countText(context.getString(R.string.terminal_exit, 0)) > 0
        val runningShown = countText(context.getString(R.string.terminal_running)) > 0
        val failedShown = countText(context.getString(R.string.terminal_failed)) > 0

        // The runtime log is one tap away and absent until asked for.
        val logHidden = countText("runtime line two") == 0
        rule.onNodeWithTag("terminal_log_toggle").performClick()
        rule.waitForIdle()
        val logShown = countText("runtime line two") > 0
        shot("29-terminal-console.png")

        // Tab wiring from the terminal back to the chat.
        rule.onNodeWithTag("project_tab_chat").performClick()
        rule.waitForIdle()
        val tabBack = tabSelections.contains(ProjectTab.CHAT)

        renderTerminal(emptyList())
        val emptyShown = countText(context.getString(R.string.terminal_empty_title)) > 0

        gate(
            "U14",
            screenShown && rows == 3 && commandShown && outputShown && exitShown &&
                runningShown && failedShown && logHidden && logShown && tabBack && emptyShown,
            "screen=$screenShown rows=$rows command=$commandShown output=$outputShown exit=$exitShown " +
                "running=$runningShown failed=$failedShown log=hidden:$logHidden,shown:$logShown " +
                "tabBack=$tabBack empty=$emptyShown",
        )
    }
}
