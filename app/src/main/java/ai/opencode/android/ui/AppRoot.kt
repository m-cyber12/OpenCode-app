package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.UiError
import ai.opencode.android.projects.Project
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.chat.SessionPanel
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.ThemeChoice
import ai.opencode.android.ui.common.toSummary
import ai.opencode.android.ui.projects.ProjectsScreen
import ai.opencode.android.ui.settings.SettingsScreen
import ai.opencode.android.ui.theme.OpenCodeTheme
import ai.opencode.android.ui.welcome.WelcomeScreen
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The only place in the UI layer that touches the process-level singletons.
 *
 * That is a rule, not a convenience: `phase6/scripts/check-ui-purity.py` fails the
 * build if any other file under `ui/` names `RuntimeManager`, `RuntimeService`,
 * `AppContainer`, `RuntimePaths`, `SecretStore` or constructs a repository, because
 * the screens have to stay pure functions of the state they are handed. This file
 * does the wiring - runtime state, project choice, repository lifetime, the file
 * picker, the diagnostics collection - and hands plain values down.
 *
 * Flow of a first run (what the Phase 6 first-run gate walks):
 *   install -> open -> WELCOME (the activity already started the runtime service,
 *   which extracts the payload and starts the server by itself) -> the supervisor
 *   reports HEALTHY -> PROJECTS (nothing exists yet) -> create a project -> CHAT.
 * A returning user with a project goes WELCOME -> CHAT.
 */

private const val ROUTE_WELCOME = "welcome"
private const val ROUTE_PROJECTS = "projects"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SESSIONS = "sessions"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppRoot(onShareDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Singletons: reached for here and nowhere else in the UI layer.
    val container = remember { AppContainer.get(context) }
    val runtime = remember { RuntimeManager.get(context) }
    val store = remember { ProjectStore.get(context) }
    val secrets = remember { container.secrets }

    val runtimeState by runtime.state.collectAsState()
    val summary = remember(runtimeState) { runtimeState.toSummary() }

    var route by rememberSaveable { mutableStateOf(ROUTE_WELCOME) }
    var themeName by rememberSaveable { mutableStateOf(ThemeChoice.SYSTEM.name) }
    var dynamicColor by rememberSaveable { mutableStateOf(true) }
    var projectName by rememberSaveable { mutableStateOf(store.activeName()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }

    val theme = runCatching { ThemeChoice.valueOf(themeName) }.getOrDefault(ThemeChoice.SYSTEM)
    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    // The repository follows the open project and the credential. AppContainer owns
    // the caching and stops the previous instance's event stream when it replaces
    // one, so a rotation or a recomposition does not leak a stream.
    val workspaceDir = remember(projectName) {
        if (projectName.isEmpty()) null else File(container.workspacesRoot(), projectName).absolutePath
    }
    val credentialReady = remember(runtimeState.status) { container.serverPasswordAvailable() }
    val repository = remember(workspaceDir, credentialReady) { container.repositoryFor(workspaceDir) }
    val stateFlow = remember(repository) { repository.state }
    val uiState by stateFlow.collectAsState()

    // Diagnostics are collected off the main thread and handed down as plain lines:
    // no screen ever calls the collector itself.
    var diagnosticsLines by remember { mutableStateOf(emptyList<String>()) }
    var diagnosticsLoading by remember { mutableStateOf(false) }
    val loadDiagnostics: () -> Unit = {
        diagnosticsLoading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { runtime.diagnostics().text }
            diagnosticsLines = text.split("\n")
            diagnosticsLoading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val staged = withContext(Dispatchers.IO) { AttachmentStager.stage(context, uri) }
                if (staged != null) repository.attach(staged)
            }
        }
    }

    // The stream is the only source of live progress; it starts once the supervisor
    // says the server is healthy and is re-asserted when the project changes.
    LaunchedEffect(summary.ready, repository) {
        if (summary.ready) repository.startStream()
    }
    LaunchedEffect(route, summary.ready) {
        if (summary.ready) repository.refresh()
        if (route == ROUTE_PROJECTS) projects = withContext(Dispatchers.IO) { store.projects() }
        if (route == ROUTE_SETTINGS && diagnosticsLines.isEmpty()) loadDiagnostics()
    }
    // First-run advance: leave WELCOME only when the agent is actually up. Where it
    // goes depends on whether a project exists - never on a timer.
    LaunchedEffect(summary.ready, projectName) {
        if (route == ROUTE_WELCOME && summary.ready) {
            route = if (projectName.isEmpty()) ROUTE_PROJECTS else ROUTE_CHAT
        }
    }

    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }
    // Both of these read the Keystore, so they are loaded off the main thread and
    // only for the screen that shows them.
    var storedIds by remember { mutableStateOf("") }
    var hardwareBacked by remember { mutableStateOf("") }
    LaunchedEffect(route) {
        if (route == ROUTE_SETTINGS) {
            storedIds = withContext(Dispatchers.IO) { container.storedProviderIdsLabel() }
            hardwareBacked = withContext(Dispatchers.IO) { container.hardwareBackedLabel() }
        }
    }
    val runtimeLine = stringResource(R.string.welcome_runtime_line, summary.opencodeVersion)

    // Availability: the three facts, combined by the pure classifier. The runtime
    // outranks the server, which outranks a single failed turn - so a provider
    // problem can never make the whole app look broken.
    val serverAvailability = when {
        uiState.serverReachable -> uiState.errorKind
        summary.ready -> AgentAvailability.SERVER_UNREACHABLE
        else -> uiState.errorKind
    }
    val turnAvailability = uiState.turnError?.kind ?: AgentAvailability.READY
    val availability = UiError.combine(summary.availability, serverAvailability, turnAvailability)

    BackHandler(enabled = route == ROUTE_SESSIONS || route == ROUTE_SETTINGS || route == ROUTE_PROJECTS) {
        route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT
    }

    DisposableEffect(repository) {
        onDispose {
            // Deliberately not stopping the stream: it belongs to the cached
            // repository, which outlives a rotation, and AppContainer stops it when
            // the workspace or the credential changes.
        }
    }

    OpenCodeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (route) {
                ROUTE_WELCOME -> WelcomeScreen(
                    runtime = summary,
                    onContinue = {
                        route = if (projectName.isEmpty()) ROUTE_PROJECTS else ROUTE_CHAT
                    },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                )

                ROUTE_PROJECTS -> ProjectsScreen(
                    projects = projects,
                    activeName = projectName,
                    runtimeLine = runtimeLine,
                    onCreate = { name ->
                        val created = store.create(name)
                        projects = store.projects()
                        projectName = created.name
                        route = ROUTE_CHAT
                    },
                    onOpen = { name ->
                        store.select(name)
                        projectName = name
                        projects = store.projects()
                        route = ROUTE_CHAT
                    },
                    // With no project open yet there is nothing to go back to except
                    // the welcome screen (where the runtime status lives).
                    onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                )

                ROUTE_SESSIONS -> SessionPanel(
                    sessions = uiState.sessions,
                    selectedSession = uiState.selectedSession,
                    transcript = uiState.transcript,
                    onBack = { route = ROUTE_CHAT },
                    onSelect = { id ->
                        repository.selectSession(id)
                        route = ROUTE_CHAT
                    },
                    onNew = {
                        repository.newSession(null)
                        route = ROUTE_CHAT
                    },
                    onDelete = { id -> repository.deleteSession(id) },
                    onRename = { id, title -> repository.renameSession(id, title) },
                )

                ROUTE_SETTINGS -> SettingsScreen(
                    runtime = summary,
                    state = uiState,
                    availability = availability,
                    diagnosticsLines = diagnosticsLines,
                    diagnosticsLoading = diagnosticsLoading,
                    storedProviderIds = storedIds,
                    hardwareBacked = hardwareBacked,
                    appVersion = appVersion,
                    theme = theme,
                    dynamicColor = dynamicColor,
                    onThemeChange = { themeName = it.name },
                    onDynamicColorChange = { dynamicColor = it },
                    onSetModel = { providerId, modelId -> repository.setModel(providerId, modelId) },
                    onClearModel = { repository.clearModel() },
                    onSaveKey = { providerId, key -> repository.provisionProvider(providerId, key, secrets) },
                    onRevokeKey = { providerId -> repository.revokeProvider(providerId, secrets) },
                    onAddMcp = { name, config, persist ->
                        val parsed = runCatching { JSONObject(config) }.getOrNull()
                        if (parsed != null) repository.addMcp(name, parsed, persist)
                    },
                    onConnectMcp = { name -> repository.connectMcp(name) },
                    onDisconnectMcp = { name -> repository.disconnectMcp(name) },
                    onRefreshMcp = { repository.refreshMcp() },
                    onBashPolicy = { policy -> repository.setBashPolicy(policy) },
                    onShareDiagnostics = {
                        loadDiagnostics()
                        onShareDiagnostics()
                    },
                    onCopyDiagnostics = { copyToClipboard(context, diagnosticsLines.joinToString("\n")) },
                    onRefreshDiagnostics = { loadDiagnostics() },
                    onRestartRuntime = { runtime.resetAndRestart() },
                    onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                )

                else -> ChatScreen(
                    state = uiState,
                    runtime = summary,
                    availability = availability,
                    projectName = projectName,
                    onSend = { text -> repository.sendPrompt(text) },
                    onDraftChange = { text -> repository.setDraft(text) },
                    onStop = { repository.abort() },
                    onRetry = { repository.retryLastTurn() },
                    onUndo = { repository.undoLastTurn() },
                    onRedo = { repository.redoLastTurn() },
                    onNewSession = { repository.newSession(null) },
                    onOpenSessions = { route = ROUTE_SESSIONS },
                    onOpenProjects = {
                        projects = store.projects()
                        route = ROUTE_PROJECTS
                    },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                    onPermissionReply = { id, reply -> repository.replyPermission(id, reply) },
                    onQuestionSubmit = { id, answers -> repository.replyQuestion(id, answers) },
                    onQuestionSkip = { id -> repository.rejectQuestion(id) },
                    onAttach = { picker.launch(ANY_CONTENT) },
                    onRemoveAttachment = { url -> repository.detach(url) },
                    onDismissBanner = { repository.clearBanner() },
                )
            }
        }
    }
}

/** The picker's mime filter: any file the user can point at. */
private const val ANY_CONTENT = "*/*"

/** Copy diagnostics to the clipboard without leaving the composable tree. */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("OpenCode diagnostics", text))
    }
}
