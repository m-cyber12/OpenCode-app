package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.CustomProviderConfig
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.ProviderSetupClassifier
import ai.opencode.android.client.UiError
import ai.opencode.android.client.WorkLog
import ai.opencode.android.memory.MemoryState
import ai.opencode.android.projects.Project
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.projects.SafProjectTransfer
import ai.opencode.android.projects.StorageController
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.StorageChoice
import ai.opencode.android.ui.changes.ChangesScreen
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.chat.SessionPanel
import ai.opencode.android.ui.files.FileNode
import ai.opencode.android.ui.files.FilesScreen
import ai.opencode.android.ui.files.OpenFile
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.ThemeChoice
import ai.opencode.android.ui.common.toSummary
import ai.opencode.android.ui.onboarding.WorkspaceOnboardingScreen
import ai.opencode.android.ui.projects.KnownWorkspace
import ai.opencode.android.ui.projects.ProjectSession
import ai.opencode.android.ui.projects.ProjectsScreen
import ai.opencode.android.ui.settings.SettingsScreen
import ai.opencode.android.ui.terminal.TerminalScreen
import ai.opencode.android.ui.theme.OpenCodeTheme
import ai.opencode.android.ui.welcome.WelcomeScreen
import android.app.Activity
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
 *   reports HEALTHY -> WORKSPACE (v4: one folder, one action) -> the first project
 *   is created and its first chat opens -> CHAT.
 * A returning user with a project goes WELCOME -> CHAT; a user who removed every
 * project goes WELCOME -> PROJECTS, because the workspace step already ran.
 */

private const val ROUTE_WELCOME = "welcome"
private const val ROUTE_PROJECTS = "projects"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SESSIONS = "sessions"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_FILES = "files"

// v7 redesign: two more project surfaces beside the chat - the agent's file
// changes stage by stage, and the shell commands it ran, as a console.
private const val ROUTE_CHANGES = "changes"
private const val ROUTE_TERMINAL = "terminal"

/**
 * Phase 10 continuation v4, item 4: the first-run workspace step. It is a route of
 * its own rather than part of WELCOME because it appears only after the runtime is
 * actually up (the folder that will be used is resolved from the platform, and
 * asking before the agent can run would be asking about a location nothing can
 * write to yet).
 */
private const val ROUTE_WORKSPACE = "workspace"

@Composable
fun AppRoot(onShareDiagnostics: () -> Unit, onOpenUrl: (String) -> Unit) {
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
    var dynamicColor by rememberSaveable { mutableStateOf(false) }
    var projectName by rememberSaveable { mutableStateOf(store.activeName()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    // v6: the projects page is a workspace view. The expanded row, the sessions
    // under each project (same server list the session panel reads), the folders
    // the page can switch to, and a tick that re-reads the sessions after a
    // rename/delete (the repository calls are async).
    var expandedProject by rememberSaveable { mutableStateOf("") }
    var sessionsByProject by remember { mutableStateOf(emptyMap<String, List<ProjectSession>>()) }
    var knownWorkspaces by remember { mutableStateOf(emptyList<KnownWorkspace>()) }
    var sessionsTick by remember { mutableStateOf(0) }

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

    // ---- Phase 7: project import/export, memory, provider state -------------

    // The memory files OpenCode itself reads (per-project AGENTS.md + the global
    // one). Read/written here and handed to Settings as plain values so the
    // screen stays pure; every write goes through OpenCode's own mechanism.
    val projectMemory = remember(container) { container.memory() }
    var memory by remember { mutableStateOf(MemoryState()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf("") }
    // Storage/import outcomes, shown wherever the action happened (projects page
    // notice, onboarding, Files). Declared BEFORE the pickers: their result
    // callbacks write it (CI #83's one compile error was this exact forward
    // reference).
    var storageMessage by remember { mutableStateOf("") }
    var sessionCounts by remember { mutableStateOf(emptyMap<String, Int>()) }

    // SAF pickers: import a document tree, export a project as a zip.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val display = uri.lastPathSegment?.substringAfterLast('/') ?: ProjectStore.DEFAULT_NAME
                        val name = store.uniqueName(display)
                        val imported = SafProjectTransfer(context, container.workspacesRoot()).importTree(uri, name)
                        store.adopt(imported.name)
                        imported.name
                    }
                }
                result.onSuccess { name ->
                    projects = store.projects()
                    projectName = name
                    importError = ""
                    // v6.1 (owner decision "keep copy, say it louder"): import IS a
                    // copy, so land back on the page that shows the copy and state
                    // plainly that the original folder was not touched - instead of
                    // silently jumping into the new project's chat.
                    storageMessage = context.getString(R.string.projects_import_done, name)
                    route = ROUTE_PROJECTS
                }.onFailure { t ->
                    importError = (t.message ?: t.javaClass.simpleName)
                }
                importing = false
            }
        }
    }
    var exportTarget by remember { mutableStateOf<Project?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val project = exportTarget
        if (uri != null && project != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { SafProjectTransfer(context, container.workspacesRoot()).exportZip(project, uri) }
                }
            }
        }
        exportTarget = null
    }

    // ---- Phase 10 continuation v4: the first-run workspace step -----------------
    //
    // Three pieces of state, and the split matters: whether the step still has to
    // run is persisted ([StorageChoice.workspaceConfirmed], set when the user
    // confirms a folder), whether a confirm is waiting for the system grant screen
    // survives the Activity recreate a storage change triggers (rememberSaveable),
    // and the one-shot guard stops a loop when the user comes back without granting.
    var workspaceConfirmed by remember { mutableStateOf(StorageChoice.workspaceConfirmed(context)) }
    var onboardingMessage by rememberSaveable { mutableStateOf("") }
    var pendingOnboarding by rememberSaveable { mutableStateOf(false) }
    var grantLaunched by rememberSaveable { mutableStateOf(false) }

    // ---- Phase 10 continuation: the project's files, in the app ---------------
    //
    // The lists come from OpenCode's own file API (the layer the agent's tools
    // use), so the user sees the real workspace, not a parallel view of it. The
    // repository already carries the active project as its instance directory,
    // which is exactly the scope `/file` resolves in.
    var filesPath by remember { mutableStateOf("") }
    var filesNodes by remember { mutableStateOf(emptyList<FileNode>()) }
    var filesLoading by remember { mutableStateOf(false) }
    var filesError by remember { mutableStateOf("") }
    var openFile by remember { mutableStateOf<OpenFile?>(null) }
    val projectDir = remember(projectName) {
        if (projectName.isEmpty()) null else File(container.workspacesRoot(), projectName)
    }

    // Where the projects live, and the actions that can change it. The snapshot is
    // rebuilt from the platform on every entry to the Files screen, so a grant made
    // in system settings shows up here without the app having to be restarted.
    val storageController = remember { StorageController.get(context) }
    var storage by remember { mutableStateOf(storageController.snapshot()) }

    /**
     * Run one storage change off the main thread, then rebuild every singleton that
     * had resolved the old root. A mode change is followed by a full recomposition
     * ([Activity.recreate]): the repository, the memory store and the project list
     * all derive from the project root, and leaving stale instances behind is how
     * an app ends up showing one location while writing to another.
     */
    fun runStorageChange(action: () -> StorageController.ChangeResult) {
        scope.launch {
            val before = withContext(Dispatchers.IO) { storageController.snapshot() }
            val result = withContext(Dispatchers.IO) {
                runCatching { action() }.getOrElse { t ->
                    StorageController.ChangeResult(
                        ok = false,
                        messageKey = StorageController.MessageKey.FOLDER_UNUSABLE,
                        detail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
            RuntimePaths.refresh()
            ProjectStore.refresh()
            StorageController.refresh()
            AppContainer.refresh()
            storageMessage = storageMessageOf(context, result)
            val after = withContext(Dispatchers.IO) { StorageController.get(context).snapshot() }
            storage = after
            if (after.mode != before.mode || after.rootPath != before.rootPath) {
                (context as? Activity)?.recreate()
            } else {
                projects = withContext(Dispatchers.IO) { ProjectStore.get(context).projects() }
            }
        }
    }

    /**
     * v4 item 4, the single action: confirm the shown folder as the workspace.
     *
     * Order matters. The folder is probed by writing into it ([StorageChoice.isUsableRoot])
     * before anything is created; when the probe fails because the app lacks All files
     * access, the SAME action opens that system screen and remembers that a confirm is
     * waiting, so the user comes back to a project rather than to the same question.
     * Only after a successful probe is the first project created - and a chat opened
     * in it, which is what "lands in chat" means (the project is the folder, the chat
     * is an OpenCode session in it; no folder is created for the chat).
     */

    // The system folder picker, for a project root the user chooses themselves.
    // Folders that cannot be handed to a POSIX runtime (SD card, cloud provider) are
    // refused with the reason - see StorageChoice.realPathOf.
    val chosenFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runStorageChange { storageController.useChosenFolder(uri) }
        }
    }
    // All files access is granted in system settings, not by a dialog, so the app
    // sends the user there and re-checks when the screen comes back.
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { runStorageChange { storageController.activateAllFilesAccess() } }

    fun confirmWorkspace() {
        scope.launch {
            val root = withContext(Dispatchers.IO) { RuntimePaths.get(context).workspaces }
            val usable = withContext(Dispatchers.IO) { StorageChoice.isUsableRoot(root) }
            if (!usable) {
                val intent = StorageChoice.allFilesAccessIntent(context)
                if (intent != null && !grantLaunched) {
                    grantLaunched = true
                    pendingOnboarding = true
                    onboardingMessage = context.getString(
                        R.string.onboarding_workspace_needs_access,
                        root.absolutePath,
                    )
                    allFilesAccessLauncher.launch(intent)
                } else {
                    pendingOnboarding = false
                    onboardingMessage = context.getString(
                        R.string.onboarding_workspace_unusable,
                        root.absolutePath,
                    )
                }
                return@launch
            }
            val created = withContext(Dispatchers.IO) { store.create(ProjectStore.FIRST_PROJECT_NAME) }
            StorageChoice.markWorkspaceConfirmed(context)
            workspaceConfirmed = true
            projectName = created.name
            projects = store.projects()
            pendingOnboarding = false
            onboardingMessage = ""
            // v6: the projects page is the post-setup surface - the new first
            // project is there, expanded, with the first chat already on the
            // server (tapping its session enters the conversation).
            expandedProject = created.name
            route = ROUTE_PROJECTS
            repository.newSession(null)
        }
    }

    // SAF: save one file out of the viewer. Whole-project export went away in v4
    // together with the storage screen it lived on - the live project folder is a
    // normal, file-manager-visible folder since v3, so "export a copy" was a second
    // way to reach files the user can already reach, and it was the button that made
    // the storage panel look like a settings screen. Single-file "Save a copy"
    // stays: it is how a binary file the viewer refuses to render gets opened
    // elsewhere.
    var saveCopyTarget by remember { mutableStateOf<File?>(null) }
    val saveCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = saveCopyTarget
        saveCopyTarget = null
        if (uri != null && source != null && projectDir != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { SafProjectTransfer(context, container.workspacesRoot()).saveFileCopy(source, uri) }
                }
                result.onSuccess { bytes ->
                    // v4: the panel this label used to feed is gone with the copy/export
                    // controls; the singular "Save a copy" outcome is reported through the
                    // same storage channel the file screen already shows.
                    filesError = ""
                    storageMessage = context.getString(R.string.files_save_copy_done, source.name, bytes)
                }.onFailure { t ->
                    filesError = context.getString(R.string.files_save_copy_failed, t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }
    fun loadFiles(relPath: String) {
        filesLoading = true
        filesError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.api.fileList(relPath).map { entry ->
                        FileNode(
                            name = entry.path.substringAfterLast('/').ifEmpty { entry.path },
                            path = entry.path,
                            isDirectory = entry.type == "directory",
                        )
                    }
                }
            }
            result.onSuccess { nodes ->
                filesNodes = nodes
                filesPath = relPath
            }.onFailure { t ->
                filesError = context.getString(R.string.files_load_failed, t.message ?: t.javaClass.simpleName)
            }
            filesLoading = false
        }
    }

    fun openProjectFile(relPath: String) {
        if (projectDir == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.api.fileContent(relPath) }
            }
            result.onSuccess { body ->
                val truncated = body.length > FILE_VIEW_LIMIT
                val shown = if (truncated) body.take(FILE_VIEW_LIMIT) else body
                openFile = OpenFile(
                    path = relPath,
                    name = relPath.substringAfterLast('/'),
                    text = shown,
                    bytes = File(projectDir, relPath).length(),
                    binary = shown.contains('\u0000'),
                    truncated = truncated,
                )
            }.onFailure { t ->
                filesError = context.getString(R.string.files_load_failed, t.message ?: t.javaClass.simpleName)
            }
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

    // Startup storage pass: if this build resolves a different default root than the
    // previous install did, the projects in the old location are moved (once, only
    // into an empty root, never overwriting - see ProjectStore.ensureMigrated). The
    // result is reported on the Files screen rather than happening silently.
    LaunchedEffect(Unit) {
        val migrated = withContext(Dispatchers.IO) {
            runCatching { storageController.migrateOnStart() }.getOrDefault(0)
        }
        if (migrated > 0) {
            projects = withContext(Dispatchers.IO) { ProjectStore.get(context).projects() }
            val now = RuntimePaths.get(context)
            storageMessage = context.getString(R.string.files_storage_moved_on_start, migrated, storageLabel(now))
        }
        storage = withContext(Dispatchers.IO) { storageController.snapshot() }
    }

    // The stream is the only source of live progress; it starts once the supervisor
    // says the server is healthy and is re-asserted when the project changes.
    LaunchedEffect(summary.ready, repository) {
        if (summary.ready) repository.startStream()
    }
    LaunchedEffect(route, summary.ready, sessionsTick) {
        if (summary.ready) repository.refresh()
        if (route == ROUTE_PROJECTS) {
            projects = withContext(Dispatchers.IO) { store.projects() }
            // Per-project conversation lists come from the server's own session
            // list grouped by directory - the same scoping the session panel uses.
            // The full (grouped) map feeds the expandable rows; the counts map
            // feeds the row's count pill.
            val rootPath = container.workspacesRoot().absolutePath
            val grouped = withContext(Dispatchers.IO) {
                runCatching { repository.api.listSessions(limit = 500) }
                    .getOrDefault(emptyList())
                    .groupBy { it.directory }
            }
            sessionsByProject = projects.mapNotNull { pr ->
                val list = grouped[java.io.File(rootPath, pr.name).absolutePath]
                    ?.map { ProjectSession(it.id, it.title, it.updatedAt) }
                    ?.sortedByDescending { it.updatedAtMs }
                    ?: return@mapNotNull null
                pr.name to list
            }.toMap()
            sessionCounts = sessionsByProject.mapValues { it.value.size }
            // The switch-party: the current root plus the remembered ones that are
            // still live folders, each with its project count read off the disk.
            val currentRoot = container.workspacesRoot()
            val legacy = withContext(Dispatchers.IO) {
                val paths = ai.opencode.android.runtime.RuntimePaths.get(context)
                listOfNotNull(paths.externalWorkspaces, paths.internalWorkspaces)
            }
            knownWorkspaces = withContext(Dispatchers.IO) {
                val previous = ai.opencode.android.runtime.StorageChoice.previousRoots(context, currentRoot, legacy)
                listOf(KnownWorkspace(
                    path = currentRoot.absolutePath,
                    projectCount = currentRoot.listFiles { f -> f.isDirectory }?.size ?: 0,
                    current = true,
                )) + previous.map { dir ->
                    KnownWorkspace(
                        path = dir.absolutePath,
                        projectCount = dir.listFiles { f -> f.isDirectory }?.size ?: 0,
                        current = false,
                    )
                }
            }
        }
        if (route == ROUTE_WORKSPACE || route == ROUTE_FILES) {
            // Reset the viewer on entry so the screen always opens on the listing.
            openFile = null
            // The first-run step prints the folder the app would use, so it has to
            // be read from the platform (a grant made a moment ago counts) - and the
            // persisted answer to "did the workspace step already run" is re-read
            // here for the same reason.
            workspaceConfirmed = StorageChoice.workspaceConfirmed(context)
            // Re-read the platform (a grant may have been made in system settings
            // while the app was in the background) before showing the panel.
            storage = withContext(Dispatchers.IO) { storageController.snapshot() }
            if (filesPath.isEmpty()) loadFiles("")
        }
        if (route == ROUTE_SETTINGS) {
            if (diagnosticsLines.isEmpty()) loadDiagnostics()
            memory = withContext(Dispatchers.IO) {
                MemoryState(
                    projectName = projectName,
                    projectContent = if (projectName.isEmpty()) "" else projectMemory.readProject(projectName),
                    projectHasRules = projectName.isNotEmpty() && projectMemory.hasProject(projectName),
                    globalContent = projectMemory.readGlobal(),
                    globalHasRules = projectMemory.hasGlobal(),
                )
            }
        }
    }
    // First-run advance: leave WELCOME only when the agent is actually up. Where it
    // goes depends on whether a project exists - never on a timer.
    LaunchedEffect(summary.ready, projectName) {
        if (route == ROUTE_WELCOME && summary.ready) {
            route = firstRunDestination(projectName, workspaceConfirmed)
        }
    }
    // A confirm that was interrupted by the system grant screen finishes itself when
    // the app comes back (the storage change recreates the Activity, so the pending
    // flag has to be saveable for this to survive).
    LaunchedEffect(route, storage.rootPath, pendingOnboarding) {
        if (route == ROUTE_WORKSPACE && pendingOnboarding) confirmWorkspace()
    }

    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }
    // Both of these read the Keystore, so they are loaded off the main thread and
    // only for the screen that shows them.
    var storedIds by remember { mutableStateOf("") }
    var storedIdList by remember { mutableStateOf(emptyList<String>()) }
    var hardwareBacked by remember { mutableStateOf("") }
    LaunchedEffect(route) {
        if (route == ROUTE_SETTINGS) {
            storedIds = withContext(Dispatchers.IO) { container.storedProviderIdsLabel() }
            storedIdList = withContext(Dispatchers.IO) { container.storedProviderIds() }
            hardwareBacked = withContext(Dispatchers.IO) { container.hardwareBackedLabel() }
        }
    }
    // "Is a model actually configured" - derived only from server + Keystore facts.
    val providerSetup = ProviderSetupClassifier.classify(
        connected = uiState.providers?.connected ?: emptyList(),
        storedIds = storedIdList,
        providersKnown = uiState.providers != null,
    )
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

    BackHandler(enabled = route == ROUTE_SESSIONS || route == ROUTE_SETTINGS || route == ROUTE_PROJECTS || route == ROUTE_FILES || route == ROUTE_CHANGES || route == ROUTE_TERMINAL) {
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
                    // Same decision as the automatic advance below: on a first run the
                    // next screen is the workspace step (one folder, one action), not an
                    // empty project list. Two spellings of one rule is how a screen gets
                    // skipped, so this is the rule.
                    onContinue = { route = firstRunDestination(projectName, workspaceConfirmed) },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                )

                ROUTE_WORKSPACE -> WorkspaceOnboardingScreen(
                    folderPath = storage.rootPath,
                    visibleToFileManagers = storage.visibleToFileManagers,
                    // A refused folder pick is reported by the storage controller, and
                    // it must be visible on the screen that is asking - a refusal the
                    // user never sees reads as "the app ignored my choice".
                    message = onboardingMessage.ifEmpty { storageMessage },
                    onPickFolder = { chosenFolderPicker.launch(null) },
                    onUseFolder = { confirmWorkspace() },
                )

                ROUTE_PROJECTS -> ProjectsScreen(
                    projects = projects,
                    activeName = projectName,
                    runtimeLine = runtimeLine,
                    onCreate = { name ->
                        // v6: creating stays on the page (the new row expanded);
                        // entering the chat is the session buttons' job.
                        val created = store.create(name)
                        projects = store.projects()
                        projectName = created.name
                        expandedProject = created.name
                    },
                    onOpen = { name ->
                        store.select(name)
                        projectName = name
                        projects = store.projects()
                        route = ROUTE_CHAT
                    },
                    workspacePath = container.workspacesRoot().absolutePath,
                    workspaces = knownWorkspaces,
                    onSwitchTo = { path ->
                        runStorageChange { storageController.switchTo(java.io.File(path)) }
                    },
                    onBrowseWorkspace = { chosenFolderPicker.launch(null) },
                    sessions = sessionsByProject,
                    expandedName = expandedProject,
                    onToggleExpand = { name ->
                        expandedProject = if (expandedProject == name) "" else name
                    },
                    onSelect = { name ->
                        store.select(name)
                        projectName = name
                    },
                    onOpenSession = { name, id ->
                        store.select(name)
                        projectName = name
                        repository.selectSession(id)
                        route = ROUTE_CHAT
                    },
                    onNewSession = { name ->
                        store.select(name)
                        projectName = name
                        repository.newSession(null)
                        route = ROUTE_CHAT
                    },
                    onRenameSession = { id, title ->
                        repository.renameSession(id, title)
                        sessionsTick++
                    },
                    onDeleteSession = { id ->
                        repository.deleteSession(id)
                        sessionsTick++
                    },
                    onRename = { old, new ->
                        val renamed = store.rename(old, new)
                        if (renamed != null) {
                            if (projectName == old) projectName = renamed.name
                            projects = store.projects()
                        }
                    },
                    onDelete = { name ->
                        // Best-effort server-side session cleanup for that directory,
                        // then the folder itself and its preference history.
                        repository.deleteSessionsForDirectory(File(container.workspacesRoot(), name).absolutePath)
                        store.delete(name)
                        if (projectName == name) projectName = store.activeName()
                        projects = store.projects()
                    },
                    onFiles = { name ->
                        val opened = store.projects().firstOrNull { it.name == name }
                        if (opened != null) {
                            store.select(opened.name)
                            projectName = opened.name
                        }
                        filesPath = ""
                        route = ROUTE_FILES
                    },
                    onImport = { importPicker.launch(null) },
                    onExport = { name ->
                        exportTarget = store.projects().firstOrNull { it.name == name }
                        exportPicker.launch("$name.zip")
                    },
                    importing = importing,
                    importError = importError,
                    sessionCounts = sessionCounts,
                    // A refused folder pick (SD card, cloud provider, unwritable) must
                    // be visible on the page that asked - the owner's fourth device
                    // pass read the silence as "the app ignored my choice".
                    notice = storageMessage,
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

                ROUTE_FILES -> FilesScreen(
                    projectName = projectName.ifEmpty { stringResource(R.string.projects_title) },
                    projectPath = projectDir?.absolutePath.orEmpty(),
                    storageMode = storage.mode,
                    storageVisibleToFileManagers = storage.visibleToFileManagers,
                    storageAppFolderBrowsableByFileManagers = storage.appFolderBrowsableByFileManagers,
                    storageCanGrantAllFilesAccess = storage.canGrantAllFilesAccess,
                    storagePendingMove = storage.pendingProjects,
                    storageMessage = storageMessage,
                    currentPath = filesPath,
                    nodes = filesNodes,
                    loading = filesLoading,
                    error = filesError,
                    openFile = openFile,
                    onOpenDir = { rel -> loadFiles(rel) },
                    onOpenFile = { rel -> openProjectFile(rel) },
                    onUp = {
                        val parent = filesPath.substringBeforeLast('/', "")
                        loadFiles(parent)
                    },
                    onCloseFile = { openFile = null },
                    onSaveCopy = { rel ->
                        val source = projectDir?.let { File(it, rel) }
                        if (source != null) {
                            saveCopyTarget = source
                            saveCopyPicker.launch(rel.substringAfterLast('/'))
                        }
                    },
                    onRequestAllFilesAccess = {
                        val intent = StorageChoice.allFilesAccessIntent(context)
                        if (intent != null) allFilesAccessLauncher.launch(intent)
                    },
                    onMoveProjects = {
                        filesError = ""
                        runStorageChange { storageController.moveProjectsIntoPlace() }
                    },
                    onBack = { route = ROUTE_CHAT },
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
                    onToggleStar = { providerId, modelId, starred ->
                        repository.setStarred(OpenCodeApi.ModelRef(providerId, modelId), starred)
                    },
                    onConnectProvider = { providerId, key -> repository.provisionProvider(providerId, key, secrets) },
                    // v6.1: the keyring (several saved keys per provider, one active).
                    providerKeys = { providerId ->
                        runCatching { container.keyring().entries(providerId) }.getOrDefault(emptyList())
                    },
                    onUseKey = { providerId, slot -> repository.activateProviderKey(providerId, slot) },
                    onDeleteKey = { providerId, slot -> repository.removeProviderKey(providerId, slot) },
                    onAddCustomProvider = { id, name, baseUrl, models, key ->
                        repository.addCustomProvider(id, name, baseUrl, CustomProviderConfig.parseModels(models), key, secrets)
                    },
                    workspacePath = storage.rootPath,
                    workspaceVisibleToFileManagers = storage.visibleToFileManagers,
                    workspaceCanGrantAllFilesAccess = storage.canGrantAllFilesAccess,
                    workspacePendingMove = storage.pendingProjects,
                    onPickWorkspace = { chosenFolderPicker.launch(null) },
                    onGrantAllFilesAccess = {
                        val intent = StorageChoice.allFilesAccessIntent(context)
                        if (intent != null) allFilesAccessLauncher.launch(intent)
                    },
                    onMoveWorkspaceProjects = {
                        filesError = ""
                        runStorageChange { storageController.moveProjectsIntoPlace() }
                    },
                    onSaveKey = { providerId, key -> repository.provisionProvider(providerId, key, secrets) },
                    onRevokeKey = { providerId -> repository.revokeProvider(providerId, secrets) },
                    onAddMcp = { name, config, persist ->
                        val parsed = runCatching { JSONObject(config) }.getOrNull()
                        if (parsed != null) repository.addMcp(name, parsed, persist)
                    },
                    onConnectMcp = { name -> repository.connectMcp(name) },
                    onDisconnectMcp = { name -> repository.disconnectMcp(name) },
                    onRefreshMcp = { repository.refreshMcp() },
                    onBashPolicy = { policy -> repository.setPermissionPolicy("bash", policy) },
                    providerSetup = providerSetup,
                    permissionPolicy = uiState.permissionPolicy,
                    onPermissionPolicy = { key, action -> repository.setPermissionPolicy(key, action) },
                    memory = memory,
                    onSaveMemory = { scopeName, text ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (scopeName == "project" && projectName.isNotEmpty()) {
                                    projectMemory.writeProject(projectName, text)
                                } else if (scopeName == "global") {
                                    projectMemory.writeGlobal(text)
                                } else {
                                    // Neither a project nor the global scope: nothing to write.
                                    Unit
                                }
                            }
                            memory = withContext(Dispatchers.IO) {
                                MemoryState(
                                    projectName = projectName,
                                    projectContent = if (projectName.isEmpty()) "" else projectMemory.readProject(projectName),
                                    projectHasRules = projectName.isNotEmpty() && projectMemory.hasProject(projectName),
                                    globalContent = projectMemory.readGlobal(),
                                    globalHasRules = projectMemory.hasGlobal(),
                                )
                            }
                        }
                    },
                    onRemoveMemory = { scopeName ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (scopeName == "project" && projectName.isNotEmpty()) {
                                    projectMemory.removeProject(projectName)
                                } else if (scopeName == "global") {
                                    projectMemory.removeGlobal()
                                } else {
                                    // Neither a project nor the global scope: nothing to remove.
                                    Unit
                                }
                            }
                            memory = withContext(Dispatchers.IO) {
                                MemoryState(
                                    projectName = projectName,
                                    projectContent = if (projectName.isEmpty()) "" else projectMemory.readProject(projectName),
                                    projectHasRules = projectName.isNotEmpty() && projectMemory.hasProject(projectName),
                                    globalContent = projectMemory.readGlobal(),
                                    globalHasRules = projectMemory.hasGlobal(),
                                )
                            }
                        }
                    },
                    onShareDiagnostics = {
                        loadDiagnostics()
                        onShareDiagnostics()
                    },
                    onCopyDiagnostics = { copyToClipboard(context, diagnosticsLines.joinToString("\n")) },
                    onRefreshDiagnostics = { loadDiagnostics() },
                    onRestartRuntime = { runtime.resetAndRestart() },
                    // Phase 10: the About / open-source section opens external pages
                    // (upstream project, licence texts, this project's own notices).
                    // The URL itself comes from strings.xml - the UI layer never
                    // compiles a URL literal, which is also what P5-G19 checks for.
                    onOpenUrl = onOpenUrl,
                    onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                )

                ROUTE_CHANGES -> {
                    val view = remember(uiState.transcript, uiState.selectedSession) {
                        uiState.transcript.session(uiState.selectedSession)
                    }
                    ChangesScreen(
                        projectName = projectName.ifEmpty { stringResource(R.string.projects_title) },
                        turns = remember(view) { WorkLog.changes(view) },
                        onBack = { route = ROUTE_CHAT },
                        onSelectTab = { tab -> route = routeForTab(tab) },
                    )
                }

                ROUTE_TERMINAL -> {
                    val view = remember(uiState.transcript, uiState.selectedSession) {
                        uiState.transcript.session(uiState.selectedSession)
                    }
                    TerminalScreen(
                        projectName = projectName.ifEmpty { stringResource(R.string.projects_title) },
                        commands = remember(view) { WorkLog.commands(view) },
                        onBack = { route = ROUTE_CHAT },
                        onSelectTab = { tab -> route = routeForTab(tab) },
                    )
                }

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
                    onOpenFiles = {
                        filesPath = ""
                        route = ROUTE_FILES
                    },
                    onOpenChanges = { route = ROUTE_CHANGES },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                    // v4 item 3: pick a starred model without leaving the chat.
                    onPickModel = { providerId, modelId -> repository.setModel(providerId, modelId) },
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

/** v7: one spelling of "which route is that tab" for every tabbed surface. */
private fun routeForTab(tab: ProjectTab): String = when (tab) {
    ProjectTab.CHAT -> ROUTE_CHAT
    ProjectTab.FILES -> ROUTE_FILES
    ProjectTab.CHANGES -> ROUTE_CHANGES
    ProjectTab.TERMINAL -> ROUTE_TERMINAL
}

/** The picker's mime filter: any file the user can point at. */
private const val ANY_CONTENT = "*/*"

/**
 * How much of a file the in-app viewer renders. The point of the screen is to see
 * what the agent wrote, not to be an editor: a 4 MB bundle log would otherwise be
 * handed to one Text composable and stutter the UI. "Save a copy" gives the whole
 * file.
 */
/**
 * Turn a storage action's outcome into one sentence the user can act on. Every
 * branch says what happened to their projects - a move is never silent, and a
 * partial move never reads as success.
 */
private fun storageMessageOf(context: Context, result: StorageController.ChangeResult): String =
    when (result.messageKey) {
        StorageController.MessageKey.MOVED ->
            context.getString(R.string.files_storage_moved, result.moved, result.from, result.to)
        StorageController.MessageKey.MOVED_SOME_FAILED ->
            context.getString(R.string.files_storage_move_failed, result.moved, result.failed)
        StorageController.MessageKey.NOTHING_TO_MOVE ->
            context.getString(R.string.files_storage_nothing_to_move)
        StorageController.MessageKey.FOLDER_UNUSABLE ->
            context.getString(R.string.files_storage_choose_failed, result.detail)
        StorageController.MessageKey.GRANT_NOT_APPLIED ->
            context.getString(R.string.files_storage_grant_not_applied)
    }

/**
 * Where a first run goes once the runtime is up (v4 item 4).
 *
 * A run with a project opens that chat; otherwise the workspace step comes first
 * (one folder, one action - confirming it creates the first project and lands in
 * that project's chat); the persisted answer "the step already ran" is the third
 * case, so an install that was set up once never sees the step again.
 *
 * Both the automatic advance and the Welcome screen's own button call this, because
 * two spellings of one rule is how a screen gets skipped.
 */
private fun firstRunDestination(projectName: String, workspaceConfirmed: Boolean): String = when {
    projectName.isNotEmpty() -> ROUTE_CHAT
    !workspaceConfirmed -> ROUTE_WORKSPACE
    else -> ROUTE_PROJECTS
}

/** The active location, named the way the storage panel names it. */
private fun storageLabel(paths: RuntimePaths): String = when (paths.mode) {
    ai.opencode.android.runtime.StorageMode.PUBLIC -> RuntimePaths.PUBLIC_PROJECTS_DIR
    ai.opencode.android.runtime.StorageMode.CHOSEN -> paths.workspaces.absolutePath
    ai.opencode.android.runtime.StorageMode.APP_EXTERNAL,
    ai.opencode.android.runtime.StorageMode.INTERNAL -> paths.workspaces.absolutePath
}

private const val FILE_VIEW_LIMIT = 200_000

/** Copy diagnostics to the clipboard without leaving the composable tree. */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("OpenCode diagnostics", text))
    }
}
