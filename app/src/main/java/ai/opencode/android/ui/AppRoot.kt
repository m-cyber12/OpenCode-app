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
import ai.opencode.android.ui.addons.AddonsScreen
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
import ai.opencode.android.ui.settings.ProvidersScreen
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

// v9.1: provider management left Settings for its own surface, opened from the
// chat's hamburger menu (owner decision: Settings stays one compact page).
private const val ROUTE_PROVIDERS = "providers"
private const val ROUTE_FILES = "files"

// v9.24 (owner): Add-ons - the app downloads big agent-consumables (the Next
// wasm compiler) once into <workspace>/.addons, agents reuse them token-free.
private const val ROUTE_ADDONS = "addons"

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
    // v9.24: Add-ons downloader. Root is a lambda - the workspace can move.
    val addonManager = remember {
        ai.opencode.android.addons.AddonManager(
            workspaceRoot = { container.workspacesRoot() },
            registryBase = context.getString(R.string.addons_registry_url),
        )
    }
    // v9.25/v9.27: GitHub project UX - the app lists repos, clones and
    // pushes with JGit over Android's platform TLS. The bundled git cannot
    // do https (NO_CURL build) and is never exec'd by the app.
    val githubClone = remember {
        ai.opencode.android.github.GithubCloneManager(
            apiBase = context.getString(R.string.github_api_url),
            webBase = context.getString(R.string.github_web_url),
        )
    }
    val githubCloneState by githubClone.state.collectAsState()
    /** The connector token, read fresh per use - never cached in state. */
    fun githubToken(): String? =
        runCatching { secrets.get(ai.opencode.android.security.GithubConnector.SECRET_NAME) }.getOrNull()
    // v9.23: whether a GitHub token is on file (Keystore). Read once at
    // composition; flipped by the Settings save/disconnect callbacks.
    var githubConnected by remember {
        mutableStateOf(
            runCatching {
                secrets.get(ai.opencode.android.security.GithubConnector.SECRET_NAME) != null
            }.getOrDefault(false),
        )
    }

    val runtimeState by runtime.state.collectAsState()
    val summary = remember(runtimeState) { runtimeState.toSummary() }

    var route by rememberSaveable { mutableStateOf(ROUTE_WELCOME) }
    // Dark is the product's designed look (the reference direction is a dark,
    // warm-gold surface); SYSTEM and LIGHT stay available in Settings.
    var themeName by rememberSaveable { mutableStateOf(ThemeChoice.DARK.name) }
    var dynamicColor by rememberSaveable { mutableStateOf(false) }
    var projectName by rememberSaveable { mutableStateOf(store.activeName()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    // v9.30 (owner): GitHub projects must LOOK different on the projects page.
    // Which names are GitHub checkouts is an on-disk fact (origin URL), so it
    // is computed off the main thread; re-keyed on the clone phase so a repo
    // that JUST finished cloning gets its mark without waiting for a reload.
    var githubProjects by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(projects, githubCloneState.phase) {
        githubProjects = withContext(Dispatchers.IO) {
            projects.filter { ai.opencode.android.github.GitSync.isGithubProject(it.dir) }
                .map { it.name }
                .toSet()
        }
    }
    // v6: the projects page is a workspace view. The expanded row, the sessions
    // under each project (same server list the session panel reads), the folders
    // the page can switch to, and a tick that re-reads the sessions after a
    // rename/delete (the repository calls are async).
    var expandedProject by rememberSaveable { mutableStateOf("") }
    var sessionsByProject by remember { mutableStateOf(emptyMap<String, List<ProjectSession>>()) }
    var knownWorkspaces by remember { mutableStateOf(emptyList<KnownWorkspace>()) }
    var sessionsTick by remember { mutableStateOf(0) }

    val theme = runCatching { ThemeChoice.valueOf(themeName) }.getOrDefault(ThemeChoice.DARK)
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

    // v9.34 (owner): the v9.31-v9.33 app-side hand-off (flip mode, wait for
    // the plan turn to end, send an "execute the plan" prompt) is GONE.
    // Upstream's experimental plan mode continues the SAME turn as the build
    // agent the moment the user answers plan_exit's question with Yes - the
    // server does the hand-off, the app only mirrors the mode chip.

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

    // v9.28 (owner): a GitHub project is ALWAYS on its project branch - the
    // moment it becomes the active project, the checkout is re-asserted.
    // v9.29 (owner): auto-push is GONE - the model pushes itself (the brief
    // teaches the $key URL form; device-proven via git-remote-https). The
    // app's job here is token ISOLATION instead: `key` may exist in the
    // server env only while a GITHUB project is active, so switching across
    // the GitHub/non-GitHub boundary restarts the server with the right env
    // (same restart the Settings token save already uses).
    LaunchedEffect(projectName) {
        githubClone.ensureProjectBranch(projectDir)
        if (projectName.isNotEmpty() && summary.ready) {
            val want = withContext(Dispatchers.IO) {
                val isGit = ai.opencode.android.github.GitSync.isGithubProject(projectDir)
                if (isGit) projectDir?.let {
                    // v9.38 (owner): "in a GitHub project the model should not
                    // need to be told to use GitHub" - the push-after-every-
                    // change contract rides into the project itself, where
                    // upstream auto-loads it (see GithubWorkspaceNotes).
                    runCatching { ai.opencode.android.runtime.GithubWorkspaceNotes.ensure(it) }
                }
                githubToken() != null && isGit
            }
            if (want != runtime.envHasGithubToken) runtime.resetAndRestart()
        }
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
                // The projects list just changed under the same root (an import,
                // a move) - the per-project conversation lists must follow it
                // (v8 fix round, owner's issue 1).
                sessionsTick++
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

    // All files access is granted in system settings, not by a dialog, so the app
    // sends the user there and re-checks when the screen comes back. v9.4 (owner's
    // bug 1): when the trip was triggered by a CUSTOM folder the app could not
    // write, the exact folder the user picked is retried on return instead of
    // bouncing them back to the same error.
    // v9.7 Sandbox: the loopback static preview servers. Owned here (the
    // screens stay pure functions of state); v9.37 (owner): ONE PER PROJECT -
    // opening another project never kills a running server ("it's fine for
    // the server from the previous project to remain running"), and a second
    // project naturally gets a DIFFERENT port because the first still holds
    // the preferred one. Stopped with the composition.
    val previewServers = remember {
        mutableStateMapOf<String, ai.opencode.android.preview.PreviewServer>()
    }
    // v9.37 (owner): "its server option should not be displayed in the new
    // project's interface." A port belongs to the project that was open when
    // it appeared (that is who started it); the mapping survives app
    // restarts and is forgotten when the port closes.
    val portOwners = remember { mutableStateMapOf<Int, String>() }
    fun persistPortOwners() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val o = org.json.JSONObject()
                    portOwners.forEach { (p, n) -> o.put(p.toString(), n) }
                    context.getSharedPreferences("opencode", 0).edit()
                        .putString("preview_port_owner", o.toString()).apply()
                }
            }
        }
    }
    // v9.37: a non-empty url IS the state "this project has something to
    // show" - the old previewOpen flag became exactly that, so it is gone.
    var previewUrl by remember { mutableStateOf("") }
    // v9.14: the project whose page the PANE is actually showing - console
    // lines must land in THAT project's console.log, not in whichever
    // project happens to be selected when a late message arrives (the
    // FocusList run surfaced stale cross-project lines).
    var previewProjectDir by remember { mutableStateOf<File?>(null) }
    // v9.10: bumped on every serve.json write so the pane reloads fresh bytes
    // even when the url stays the same (the agent edited the page in place).
    var previewReload by remember { mutableStateOf(0L) }
    // v9.35: the preview lives in the CHAT now - a console under the TODO
    // panel plus a full-screen overlay fading over the conversation.
    // The phase machine: CLOSED -> OPENING -> OPEN -> CLOSING -> CLOSED,
    // each hop a finite 240ms fade window owned by the LaunchedEffect below.
    var previewPhase by remember {
        mutableStateOf(ai.opencode.android.ui.chat.PreviewState.CLOSED)
    }
    var previewPort by remember { mutableStateOf(0) }
    // v9.37 (owner): "show a message or indicator on the console to let the
    // user know that the project has been updated" - a label res id
    // ("Project updated" for a fresh hand-off/restarted server, "Reloaded"
    // for reconnects after an app restart) that arrives and leaves.
    var previewFlashRes by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        onDispose {
            previewServers.values.forEach { runCatching { it.stop() } }
            previewServers.clear()
        }
    }

    LaunchedEffect(previewPhase) {
        if (previewPhase == ai.opencode.android.ui.chat.PreviewState.OPENING) {
            // The overlay's fade tween runs 240ms; give it the window.
            kotlinx.coroutines.delay(300)
            previewPhase = ai.opencode.android.ui.chat.PreviewState.OPEN
        } else if (previewPhase == ai.opencode.android.ui.chat.PreviewState.CLOSING) {
            kotlinx.coroutines.delay(300)
            // v9.35.4 (owner): exit fades back to the LIVE console - the
            // server stays open; only the overlay closes. Tapping the bar
            // opens it again.
            previewPhase = ai.opencode.android.ui.chat.PreviewState.CLOSED
        }
    }

    // v9.37: a port's owner was persisted before this process started -
    // rehydrate the map so a restart neither forgets project ports nor
    // re-attributes an old project's server to whichever project opens.
    LaunchedEffect(Unit) {
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                context.getSharedPreferences("opencode", 0).getString("preview_port_owner", "{}")
            }.getOrNull().orEmpty()
        }
        runCatching {
            val o = org.json.JSONObject(raw)
            for (k in o.keys()) portOwners[k.toInt()] = o.getString(k)
        }
    }

    /** v9.37 (owner): the console's acknowledgement - a label that arrives,
     * stays ~2.4s, and leaves. Finite by construction (one delayed reset). */
    fun flash(resId: Int) {
        previewFlashRes = resId
        scope.launch {
            kotlinx.coroutines.delay(2400)
            if (previewFlashRes == resId) previewFlashRes = 0
        }
    }

    /** v9.35: open the chat preview page - ONLY ever from a user tap
     *  (v9.37: a server starting never opens it; the console asks instead). */
    fun openChatPreview(url: String, port: Int, dir: File?) {
        previewUrl = url
        previewPort = port
        if (dir != null) previewProjectDir = dir
        previewPhase = if (previewPhase == ai.opencode.android.ui.chat.PreviewState.CLOSED) {
            ai.opencode.android.ui.chat.PreviewState.OPENING
        } else {
            ai.opencode.android.ui.chat.PreviewState.OPEN
        }
        route = ROUTE_CHAT
    }

    /**
     * v9.37 (owner): a project got something to show - light the console,
     * reload an open pane, but NEVER grab the screen. The user's tap on
     * "See What You've Made" decides when the page appears.
     */
    fun setPreviewTarget(url: String, port: Int, dir: File?, stamp: Long, flashRes: Int) {
        previewUrl = url
        previewPort = port
        if (dir != null) previewProjectDir = dir
        previewReload = stamp
        if (flashRes != 0) flash(flashRes)
    }

    /** v9.35.4: the same sequence in reverse - the overlay fades back into
     *  the console bar; the preview session itself stays live. */
    fun closeChatPreview() {
        if (previewPhase == ai.opencode.android.ui.chat.PreviewState.OPEN ||
            previewPhase == ai.opencode.android.ui.chat.PreviewState.OPENING
        ) {
            previewPhase = ai.opencode.android.ui.chat.PreviewState.CLOSING
        }
    }

    /**
     * Start (or reuse) the loopback server for [dir] off the main thread.
     * v9.12: extracted from openPreviewFor so the headless capture engine can
     * get a server WITHOUT flipping any preview UI state. Returns null when
     * the server could not start. v9.37: one server per directory - opening
     * another project never stops this one, and a fresh project's server
     * lands on a different port by itself (the preferred one is taken).
     */
    suspend fun ensureServerFor(dir: File): ai.opencode.android.preview.PreviewServer? {
        val key = dir.absolutePath
        val existing = previewServers[key]
        val server = withContext(Dispatchers.IO) {
            if (existing != null && existing.running) {
                existing
            } else {
                runCatching { existing?.stop() }
                ai.opencode.android.preview.PreviewServer(dir).also { fresh ->
                    runCatching { fresh.start() }
                }
            }
        }
        return if (server.running) {
            previewServers[key] = server
            server
        } else {
            previewServers.remove(key)
            null
        }
    }

    /**
     * v9.37: resolve what a project's serve.json points at: a declared port
     * is the agent's own server; without one, the app serves the project
     * statically. Returns null when the static server could not start.
     */
    suspend fun previewTargetFor(
        dir: File,
        declaredPort: Int,
        cleanPath: String,
    ): Pair<String, Int>? {
        return if (declaredPort in 1..65535) {
            "http://127.0.0.1:$declaredPort$cleanPath" to declaredPort
        } else {
            val server = ensureServerFor(dir) ?: return null
            "http://127.0.0.1:${server.port}$cleanPath" to server.port
        }
    }

    /**
     * v9.38 (owner): "The server should also support being stopped - both
     * when the project is finished and whenever the agent decides to shut it
     * down." Stops THIS project's in-process static server and, when the
     * console was showing it, clears the bar and frees the port ownership.
     * A declared external port is the agent's own process: the app never
     * kills it (its livePorts watcher notices when it closes) - here the
     * project simply stops following it.
     */
    suspend fun stopProjectPreview(dir: File?) {
        if (dir != null) {
            val srv = previewServers.remove(dir.absolutePath)
            withContext(Dispatchers.IO) { runCatching { srv?.stop() } }
        }
        val aboutThisProject = dir == null ||
            previewProjectDir?.absolutePath == dir.absolutePath || previewUrl.isEmpty()
        if (aboutThisProject) {
            if (previewPort > 0 && portOwners[previewPort] == projectName) {
                portOwners.remove(previewPort)
                persistPortOwners()
            }
            previewUrl = ""
            previewPort = 0
            previewProjectDir = null
            if (previewPhase != ai.opencode.android.ui.chat.PreviewState.CLOSED) {
                previewPhase = ai.opencode.android.ui.chat.PreviewState.CLOSED
            }
        }
    }

    // .preview/capture.json {"name": "02-after-login-fix", "path": "/x.html",
    // "port": 8080} and the app loads that page in an OFF-SCREEN WebView at
    // device size - no pane needs to be open, no tab needs to be visible -
    // then files the settled frame in the project-root screenshots/ folder
    // (v9.13, owner: next to the project's other files, not hidden in
    // .preview) and refreshes latest.png. Omitted port = the app's own
    // static server. Same mtime gating as serve.json: stale markers inert.
    val headlessCapture = remember { ai.opencode.android.preview.HeadlessCapture(context) }
    LaunchedEffect(projectDir) {
        val dir = projectDir ?: return@LaunchedEffect
        val marker = File(dir, ".preview/capture.json")
        var handled = withContext(Dispatchers.IO) { if (marker.exists()) marker.lastModified() else 0L }
        while (true) {
            kotlinx.coroutines.delay(1500)
            val stamp = withContext(Dispatchers.IO) { if (marker.exists()) marker.lastModified() else 0L }
            if (stamp > handled) {
                handled = stamp
                val declared = withContext(Dispatchers.IO) {
                    runCatching { org.json.JSONObject(marker.readText()) }.getOrNull()
                }
                val name = (declared?.optString("name").orEmpty()).ifEmpty { "shot" }
                val declaredPort = declared?.optInt("port", 0) ?: 0
                val requested = (declared?.optString("path", "/") ?: "/").ifEmpty { "/" }
                val cleanPath = if (requested.startsWith("/")) requested else "/$requested"
                val url = if (declaredPort in 1..65535) {
                    "http://127.0.0.1:$declaredPort$cleanPath"
                } else {
                    val server = ensureServerFor(dir) ?: continue
                    "http://127.0.0.1:${server.port}$cleanPath"
                }
                val metrics = context.resources.displayMetrics
                headlessCapture.capture(url, metrics.widthPixels, metrics.heightPixels) { bitmap, reason, probeRaw ->
                    scope.launch {
                        val resultLine = withContext(Dispatchers.IO) {
                            runCatching {
                                if (bitmap != null) {
                                    val preview = File(dir, ".preview")
                                    preview.mkdirs()
                                    val shot = ai.opencode.android.preview.ShotStore.nextFile(dir, name)
                                    shot.outputStream().use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                                    }
                                    File(preview, "latest.png").outputStream().use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                                    }
                                    // v9.36 (owner): the agent's cheap loop -
                                    // the small JPEG it may read, the text
                                    // report it should read FIRST, and the
                                    // frame hash that catches "my edit did
                                    // not reach the render" before another
                                    // screenshot is ever burned.
                                    ai.opencode.android.preview.ShotStore.writeAgentJpeg(bitmap, preview)
                                    val sig = ai.opencode.android.preview.ShotStore.frameSig(bitmap)
                                    val sigFile = File(preview, "frame.sig")
                                    val frame = when {
                                        !sigFile.isFile -> "first"
                                        runCatching { sigFile.readText().trim() }.getOrNull() == sig -> "unchanged-vs-prev"
                                        else -> "changed"
                                    }
                                    runCatching { sigFile.writeText(sig + "\n") }
                                    if (probeRaw != null) {
                                        runCatching { File(preview, ai.opencode.android.preview.RenderProbe.REPORT_FILE).writeText(probeRaw + "\n") }
                                    }
                                    val seq = shot.name.take(3).toIntOrNull() ?: 0
                                    val budget = if (seq > 8) {
                                        "\nBUDGET shot #$seq this project: every image read rides on ALL later steps - verify with the REPORT line and report.json; read .preview/${ai.opencode.android.preview.ShotStore.AGENT_VIEW} at most once per fix round, never a PNG."
                                    } else {
                                        ""
                                    }
                                    "CAPTURE saved ${ai.opencode.android.preview.ShotStore.SHOTS_DIR}/${shot.name} (${bitmap.width}x${bitmap.height}) view=${ai.opencode.android.preview.ShotStore.AGENT_VIEW} url=$url\n" +
                                        ai.opencode.android.preview.RenderProbe.digest(probeRaw, frame) + budget
                                } else {
                                    "CAPTURE FAILED name=$name url=$url reason=$reason"
                                }
                            }.getOrElse { e -> "CAPTURE FAILED name=$name url=$url reason=save-error:${e.message}" }
                        }
                        // v9.14: the capture pipeline must never be a silent
                        // no-op again - every outcome lands in console.log.
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val folder = File(dir, ".preview")
                                folder.mkdirs()
                                val log = File(folder, "console.log")
                                if (log.length() > 64_000) log.writeText("")
                                log.appendText(resultLine + "\n")
                            }
                        }
                    }
                }
            }
        }
    }

    // v9.9 (owner): the app WATCHES the loopback - a connect-probe of ~15
    // ports every 2.5s is microseconds of kernel work (Android denies
    // /proc/net/tcp, so probing is the honest way to know). v9.37 (owner):
    // a server appearing NEVER opens the preview - "the app shouldn't
    // automatically open the preview when a server starts." It is instead
    // attributed to the project that was open when it appeared (persisted, so
    // other projects' consoles never list it), and the console asks.
    var livePorts by remember { mutableStateOf(listOf<Int>()) }
    LaunchedEffect(Unit) {
        val scanner = ai.opencode.android.preview.PortScanner()
        var seen: Set<Int>? = null
        while (true) {
            livePorts = withContext(Dispatchers.IO) { scanner.scan() }
            val current = livePorts.toSet()
            val before = seen
            val project = projectName
            if (before == null) {
                // App (re)start: a server still up from before reconnects the
                // console of ITS project only, with the "Reloaded" nod.
                // A live target the serve.json watcher already armed is kept
                // (it carries the PATH the port scan cannot know).
                if (project.isNotEmpty()) {
                    val mine = current.filter { portOwners[it] == project }.sorted()
                    if (mine.isNotEmpty()) {
                        if (previewUrl.isEmpty() || previewPort !in current) {
                            val target = mine.first()
                            setPreviewTarget(
                                "http://127.0.0.1:$target/",
                                target,
                                null,
                                System.currentTimeMillis(),
                                R.string.sandbox_console_reloaded,
                            )
                        } else {
                            flash(R.string.sandbox_console_reloaded)
                        }
                    }
                }
            } else {
                val fresh = current - before
                if (fresh.isNotEmpty()) {
                    // The project that is open when a port APPEARS is the one
                    // whose agent started the server - that is the ownership
                    // signal, and the answer to "a new project should open its
                    // server on a different port": 8080 was taken by the old
                    // project, so a model following its brief lands on the
                    // next free one, and the brief says to record it in
                    // serve.json.
                    if (project.isNotEmpty()) {
                        fresh.forEach { portOwners[it] = project }
                        persistPortOwners()
                        // v9.38 (owner): "a foreign live port must never be
                        // this project's target" - a target is only kept if
                        // it is live AND owned by THIS project; otherwise the
                        // project's freshest port takes it (this is also what
                        // re-points the bar after the user switches back).
                        val targetMineAndLive = previewUrl.isNotEmpty() &&
                            previewPort in current && portOwners[previewPort] == project
                        if (!targetMineAndLive && previewUrl.isNotEmpty() && previewPort in fresh) {
                            // Same port came back (restart) - an open pane
                            // reloads; a closed one just lights the bar.
                            setPreviewTarget(
                                previewUrl, previewPort, null,
                                System.currentTimeMillis(),
                                R.string.sandbox_console_updated,
                            )
                        } else if (!targetMineAndLive) {
                            val firstPort = fresh.min()
                            setPreviewTarget(
                                "http://127.0.0.1:$firstPort/",
                                firstPort,
                                null,
                                System.currentTimeMillis(),
                                R.string.sandbox_console_updated,
                            )
                        } else {
                            flash(R.string.sandbox_console_updated)
                        }
                    }
                }
                val gone = before - current
                if (gone.isNotEmpty()) {
                    // A closed port is unowned again - the next project to
                    // see it appear owns it.
                    gone.forEach { portOwners.remove(it) }
                    persistPortOwners()
                }
            }
            seen = current
            kotlinx.coroutines.delay(2500)
        }
    }

    // v9.8/v9.9: the AGENT'S hand-off. Writing .preview/serve.json into the
    // project is how it says "there is something to look at":
    //   {"port": 8080, "path": "/"} -> preview the agent's OWN server (any port)
    //   {"path": "/index.html"}     -> the app serves the project statically
    // v9.37 (owner): a write never opens the page - it updates the console
    // target, reloads an OPEN pane with fresh bytes, and flashes "Project
    // updated" so the user knows the model moved something. An EXISTING
    // marker still arms the console when the project opens (quietly, no
    // flash), so the bar survives app restarts.
    LaunchedEffect(projectDir) {
        val dir = projectDir
        // v9.38 (owner): "when I switched to the second project, the previous
        // server was still displayed... nothing related to the server should
        // be displayed in a new chat." A preview target belongs to exactly one
        // project; crossing the boundary clears it first, so bar, door and
        // overlay - all driven by this one state - show NOTHING of the old
        // project and nothing that was never this project's.
        if (previewUrl.isNotEmpty() && previewProjectDir?.absolutePath != dir?.absolutePath) {
            previewUrl = ""
            previewPort = 0
            previewProjectDir = null
            if (previewPhase != ai.opencode.android.ui.chat.PreviewState.CLOSED) {
                previewPhase = ai.opencode.android.ui.chat.PreviewState.CLOSED
            }
        }
        if (dir == null) return@LaunchedEffect
        val marker = File(dir, ".preview/serve.json")
        var handled = withContext(Dispatchers.IO) { if (marker.exists()) marker.lastModified() else 0L }
        if (handled > 0L) {
            val declared = withContext(Dispatchers.IO) {
                runCatching { org.json.JSONObject(marker.readText()) }.getOrNull()
            }
            val p = declared?.optInt("port", 0) ?: 0
            val r = (declared?.optString("path", "/") ?: "/").ifEmpty { "/" }
            val clean = if (r.startsWith("/")) r else "/$r"
            // v9.38 (owner's "No such file or directory" after an app
            // restart): the START-UP arm has to be believed before the bar
            // offers it - a marker outlives the server it described, and the
            // static fallback may point at a path that is not at the project
            // root at all. A declared port must actually answer; a static
            // path must actually exist. A lie at start-up stays dark and
            // quiet - any NEW hand-off re-arms the console as before.
            if (declared?.optBoolean("stop", false) != true) {
                val live = p !in 1..65535 || withContext(Dispatchers.IO) {
                    runCatching { ai.opencode.android.preview.PortScanner().scan().contains(p) }
                        .getOrDefault(true)
                }
                val existsAtRoot = p in 1..65535 || withContext(Dispatchers.IO) {
                    runCatching { File(dir, clean.substringBefore('?').removePrefix("/")).exists() }
                        .getOrDefault(true)
                }
                if (live && existsAtRoot) {
                    // previewTargetFor covers both kinds: a declared port (the
                    // agent's server, wherever it runs) or the app's own static
                    // server for the project - started quietly, never opened.
                    previewTargetFor(dir, p, clean)?.let { (u, port) ->
                        setPreviewTarget(u, port, dir, handled, 0)
                    }
                }
            }
        }
        // v9.38 (owner): returning to a project whose OWNED port is still
        // live re-arms the bar without needing a fresh write (the switch away
        // just cleared it) - quietly, and only while nothing claimed it.
        if (previewUrl.isEmpty() && projectName.isNotEmpty()) {
            val mineNow = livePorts.filter { portOwners[it] == projectName }.minOrNull()
            if (mineNow != null) {
                setPreviewTarget(
                    "http://127.0.0.1:$mineNow/",
                    mineNow,
                    dir,
                    System.currentTimeMillis(),
                    0,
                )
            }
        }
        while (true) {
            kotlinx.coroutines.delay(1500)
            val stamp = withContext(Dispatchers.IO) { if (marker.exists()) marker.lastModified() else 0L }
            if (stamp > handled) {
                handled = stamp
                val declared = withContext(Dispatchers.IO) {
                    runCatching { org.json.JSONObject(marker.readText()) }.getOrNull()
                }
                // v9.38 (owner): {"stop": true} in serve.json is the agent's
                // shutdown request for the APP's server of this project
                // (killing a server it started itself stays its own bash
                // business). Quiet by design - the bar simply goes dark.
                if (declared?.optBoolean("stop", false) == true) {
                    stopProjectPreview(dir)
                    continue
                }
                val declaredPort = declared?.optInt("port", 0) ?: 0
                val requested = (declared?.optString("path", "/") ?: "/").ifEmpty { "/" }
                val cleanPath = if (requested.startsWith("/")) requested else "/$requested"
                // v9.37: whoever DECLARES a port in their serve.json owns it
                // for the console filter - the declaration is stronger than
                // the appear-time heuristic (a server hand-over between
                // projects is then honest, and late attribution can't strand
                // the row).
                if (declaredPort in 1..65535 && projectName.isNotEmpty()) {
                    portOwners[declaredPort] = projectName
                    persistPortOwners()
                }
                val target = previewTargetFor(dir, declaredPort, cleanPath)
                if (target != null) {
                    setPreviewTarget(
                        target.first, target.second, dir, stamp,
                        if (previewPhase == ai.opencode.android.ui.chat.PreviewState.OPEN ||
                            previewPhase == ai.opencode.android.ui.chat.PreviewState.OPENING
                        ) {
                            0 // the pane is on screen and reloading - no flash
                        } else {
                            R.string.sandbox_console_updated
                        },
                    )
                }
            }
        }
    }

    var pendingChosenTree by remember { mutableStateOf<android.net.Uri?>(null) }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pendingTree = pendingChosenTree
        pendingChosenTree = null
        if (pendingTree != null && StorageChoice.hasAllFilesAccess()) {
            runStorageChange { storageController.useChosenFolder(pendingTree) }
        } else {
            runStorageChange { storageController.activateAllFilesAccess() }
        }
    }

    // The system folder picker, for a project root the user chooses themselves.
    // Folders that cannot be handed to a POSIX runtime (SD card, cloud provider) are
    // refused with the reason - see StorageChoice.realPathOf.
    //
    // v9.4 (owner's bug 1): "cannot write here" used to be a dead end when the real
    // cause was the missing All files access grant - the folder was fine, the app
    // just was not allowed into shared storage yet. Now that exact case explains
    // itself and walks the user to the system switch, then connects the folder
    // they picked when they come back with the grant.
    val chosenFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val dir = withContext(Dispatchers.IO) { StorageChoice.realPathOf(uri) }
                val writable = dir != null && withContext(Dispatchers.IO) { StorageChoice.isUsableRoot(dir) }
                val grant = StorageChoice.allFilesAccessIntent(context)
                if (dir != null && !writable && grant != null && !StorageChoice.hasAllFilesAccess()) {
                    val text = context.getString(
                        R.string.onboarding_workspace_grant_for_folder,
                        dir.absolutePath,
                    )
                    onboardingMessage = text
                    storageMessage = text
                    pendingChosenTree = uri
                    allFilesAccessLauncher.launch(grant)
                } else {
                    runStorageChange { storageController.useChosenFolder(uri) }
                }
            }
        }
    }

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
                    val root = projectDir
                    repository.api.fileList(relPath).map { entry ->
                        val isDir = entry.type == "directory"
                        // v9.38 (owner): the listing became a real file
                        // manager - a row that looks like a file manager row
                        // needs the file manager's facts, and they ride the
                        // same IO tick (one length()/lastModified() per row).
                        val f = if (root != null) File(root, entry.path) else null
                        FileNode(
                            name = entry.path.substringAfterLast('/').ifEmpty { entry.path },
                            path = entry.path,
                            isDirectory = isDir,
                            bytes = if (isDir) -1L else runCatching { f?.length() ?: -1L }.getOrDefault(-1L),
                            modifiedMs = runCatching { f?.lastModified() ?: 0L }.getOrDefault(0L),
                        )
                    }.sortedWith(
                        compareByDescending<FileNode> { it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    )
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
    // v8 fix round (owner's issue 1): the workspace root is a key on purpose.
    // A switch that does not recreate the Activity used to leave this effect
    // dormant - the projects list swapped but the conversation lists were never
    // re-fetched until the user navigated away and back.
    LaunchedEffect(route, summary.ready, sessionsTick, storage.rootPath) {
        if (summary.ready) repository.refresh()
        if (route == ROUTE_PROJECTS) {
            projects = withContext(Dispatchers.IO) { store.projects() }
            // Per-project conversation lists come from the server's own session
            // list grouped by directory - the same scoping the session panel uses.
            // The full (grouped) map feeds the expandable rows; the counts map
            // feeds the row's count pill.
            val rootPath = container.workspacesRoot().absolutePath
            suspend fun fetchGrouped() = withContext(Dispatchers.IO) {
                runCatching { repository.api.listSessions(limit = 500) }
                    .getOrDefault(emptyList())
                    .groupBy { it.directory }
            }
            var grouped = fetchGrouped()
            // Right after a workspace switch the recreated Activity runs this
            // effect exactly once, and the server can still be settling on the
            // new root at that moment: an empty answer while projects exist on
            // disk is more likely "too early" than "no conversations". Three
            // finite retries, one second apart - then the empty answer stands.
            var retries = 0
            while (grouped.isEmpty() && projects.isNotEmpty() && retries < 3) {
                kotlinx.coroutines.delay(1_000)
                retries++
                grouped = fetchGrouped()
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
        // v7: the terminal page appends the runtime log, so entering it loads the
        // same diagnostics text the Settings view shows (once; refresh lives there).
        if (route == ROUTE_TERMINAL && diagnosticsLines.isEmpty()) loadDiagnostics()
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
        // v9.1: the Providers surface shows the same Keystore facts Settings used to.
        if (route == ROUTE_SETTINGS || route == ROUTE_PROVIDERS) {
            storedIds = withContext(Dispatchers.IO) { container.storedProviderIdsLabel() }
            storedIdList = withContext(Dispatchers.IO) { container.storedProviderIds() }
            hardwareBacked = withContext(Dispatchers.IO) { container.hardwareBackedLabel() }
        }
        // v9.25.1: the GitHub-connector flag re-reads the Keystore whenever a
        // surface that depends on it opens. The one-shot `remember` read froze
        // whatever a cold start saw for the whole process lifetime - so a token
        // pasted (or lost) later never changed the Projects page until relaunch.
        if (route == ROUTE_PROJECTS || route == ROUTE_SETTINGS) {
            githubConnected = withContext(Dispatchers.IO) {
                runCatching {
                    secrets.get(ai.opencode.android.security.GithubConnector.SECRET_NAME) != null
                }.getOrDefault(githubConnected)
            }
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

    BackHandler(enabled = route == ROUTE_SESSIONS || route == ROUTE_SETTINGS || route == ROUTE_PROVIDERS || route == ROUTE_PROJECTS || route == ROUTE_FILES || route == ROUTE_CHANGES || route == ROUTE_TERMINAL || route == ROUTE_ADDONS) {
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
            // Edge-to-edge: the Surface paints under the system bars; the content
            // pads by the safe-drawing insets (status bar, gesture bar, IME).
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // v9.34 (owner: the auto-jump to the live preview "has no
            // animation, it's not a smooth transition"). EVERY route change
            // now crossfades - one finite 240ms tween, so the UI gates'
            // waitForIdle still settles and the no-infinite-animation rule
            // holds. The lambda switches on its own parameter: during the
            // fade both screens are composed, and the outgoing one must keep
            // rendering its OWN route, not the new value of `route`.
            Crossfade(
                targetState = route,
                animationSpec = tween(durationMillis = 240),
                label = "route_transition",
            ) { screen ->
            when (screen) {
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
                        // v9.26/v9.28 (owner): a git project lives on ONE
                        // branch (opencode/<project>) and every session must
                        // be ON it - re-assert, never assume.
                        githubClone.ensureProjectBranch(File(container.workspacesRoot(), name))
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
                        // v9.38 (owner): "the server should support being
                        // stopped when the project is finished" - deleting a
                        // project ENDS it: its in-process preview server
                        // stops and its port claims are freed.
                        run {
                            val dead = File(container.workspacesRoot(), name).absolutePath
                            previewServers.keys.filter { it == dead }.forEach { key ->
                                runCatching { previewServers.remove(key)?.stop() }
                            }
                            val gonePorts = portOwners.keys.filter { portOwners[it] == name }
                            if (gonePorts.isNotEmpty()) {
                                gonePorts.forEach { portOwners.remove(it) }
                                persistPortOwners()
                            }
                            if (previewProjectDir?.absolutePath == dead && previewUrl.isNotEmpty()) {
                                previewUrl = ""
                                previewPort = 0
                                previewProjectDir = null
                                if (previewPhase != ai.opencode.android.ui.chat.PreviewState.CLOSED) {
                                    previewPhase = ai.opencode.android.ui.chat.PreviewState.CLOSED
                                }
                            }
                        }
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
                    // v9.26 (owner): GitHub lives INSIDE the create card - a
                    // toggle (or, before first setup, a Connect key) and a
                    // repository dropdown; Create then clones the repo as the
                    // project and parks it on its opencode/<project> branch.
                    // v9.30 (owner): GitHub projects carry the mark in the list.
                    githubProjects = githubProjects,
                    githubConnected = githubConnected,
                    githubRepos = githubCloneState.repos,
                    githubBusy = githubCloneState.phase == ai.opencode.android.github.GithubCloneManager.Phase.LISTING ||
                        githubCloneState.phase == ai.opencode.android.github.GithubCloneManager.Phase.CLONING,
                    githubProgress = githubCloneState.progress,
                    githubError = githubCloneState.error,
                    onLoadGithubRepos = {
                        githubToken()?.let { githubClone.listRepos(it) }
                    },
                    onConnectGithub = { route = ROUTE_SETTINGS },
                    onCreateFromRepo = { repo, typedName ->
                        val token = githubToken()
                        if (token != null) {
                            // A fresh, unique, EMPTY project dir - create() dedupes.
                            // The typed name wins; empty = the repo's own name.
                            val created = store.create(typedName.ifBlank { repo.name })
                            projects = store.projects()
                            githubClone.clone(repo, token, created.dir) {
                                // Cloned and on its project branch: surface it
                                // like any new project.
                                projects = store.projects()
                                projectName = created.name
                                expandedProject = created.name
                            }
                        }
                    },
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
                        // v9.28 (owner): sessions never leave the project branch.
                        githubClone.ensureProjectBranch(projectDir)
                        repository.newSession(null)
                        route = ROUTE_CHAT
                    },
                    onDelete = { id -> repository.deleteSession(id) },
                    onRename = { id, title -> repository.renameSession(id, title) },
                )

                ROUTE_FILES -> FilesScreen(
                    // v9.35 (owner): the preview itself moved into the chat
                    // screen (console under the TODO panel + morph overlay);
                    // the Sandbox keeps the port door, and opening it now
                    // walks the user to the chat where the preview lives.
                    livePorts = livePorts.filter { it == previewPort || portOwners[it] == projectName },
                    onOpenPort = { port ->
                        openChatPreview(
                            "http://127.0.0.1:$port/",
                            port,
                            projectDir,
                        )
                    },
                    onSelectTab = { tab -> route = routeForTab(tab) },
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
                    appVersion = appVersion,
                    theme = theme,
                    dynamicColor = dynamicColor,
                    onThemeChange = { themeName = it.name },
                    onDynamicColorChange = { dynamicColor = it },
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
                    onAddMcp = { name, config, persist ->
                        // Returns whether the config parsed: the form shows an
                        // inline error instead of swallowing a bad paste (v8 fix
                        // round, owner's issue 7 - the button used to "do nothing").
                        val parsed = runCatching { JSONObject(config) }.getOrNull()
                        if (parsed != null) repository.addMcp(name, parsed, persist)
                        parsed != null
                    },
                    onConnectMcp = { name -> repository.connectMcp(name) },
                    onDisconnectMcp = { name -> repository.disconnectMcp(name) },
                    onRefreshMcp = { repository.refreshMcp() },
                    onBashPolicy = { policy -> repository.setPermissionPolicy("bash", policy) },
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
                    // v9.23 GitHub connector: Keystore in, env `key` out. A save
                    // or disconnect restarts the local server so the agent's
                    // environment is correct IMMEDIATELY, not after the next
                    // app launch.
                    githubConnected = githubConnected,
                    onSaveGithubToken = save@{ raw ->
                        if (!ai.opencode.android.security.GithubConnector.looksLikeToken(raw)) return@save false
                        val ok = runCatching {
                            secrets.put(
                                ai.opencode.android.security.GithubConnector.SECRET_NAME,
                                ai.opencode.android.security.GithubConnector.normalize(raw),
                            )
                        }.isSuccess
                        if (ok) {
                            githubConnected = true
                            runtime.resetAndRestart()
                        }
                        ok
                    },
                    onRemoveGithubToken = {
                        runCatching { secrets.delete(ai.opencode.android.security.GithubConnector.SECRET_NAME) }
                        githubConnected = false
                        runtime.resetAndRestart()
                    },
                    // Phase 10: the About / open-source section opens external pages
                    // (upstream project, licence texts, this project's own notices).
                    // The URL itself comes from strings.xml - the UI layer never
                    // compiles a URL literal, which is also what P5-G19 checks for.
                    onOpenUrl = onOpenUrl,
                    onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                )

                // v9.1: the Providers surface - the provider/model/keys sections that
                // used to sit inside Settings, unchanged in behaviour, plus the `+`
                // that opens the manual custom-provider form.
                ROUTE_PROVIDERS -> ProvidersScreen(
                    state = uiState,
                    availability = availability,
                    storedProviderIds = storedIds,
                    hardwareBacked = hardwareBacked,
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
                    onRevokeKey = { providerId -> repository.revokeProvider(providerId, secrets) },
                    providerSetup = providerSetup,
                    onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                )

                // v9.24: Add-ons. The manager lives at the top of AppRoot so a
                // download survives navigating away from this screen.
                ROUTE_ADDONS -> {
                    LaunchedEffect(Unit) { addonManager.refresh() }
                    val addonState by addonManager.state.collectAsState()
                    AddonsScreen(
                        state = addonState,
                        onDownload = { spec, version -> addonManager.download(spec, version) },
                        onCancel = { addonManager.cancel() },
                        onDelete = { name -> addonManager.delete(name) },
                        onBack = { route = if (projectName.isEmpty()) ROUTE_WELCOME else ROUTE_CHAT },
                    )
                }

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
                        runtimeLog = diagnosticsLines,
                        onBack = { route = ROUTE_CHAT },
                        onSelectTab = { tab -> route = routeForTab(tab) },
                    )
                }

                else -> ChatScreen(
                    state = uiState,
                    runtime = summary,
                    availability = availability,
                    projectName = projectName,
                    githubProject = projectName in githubProjects,
                    onSend = { text -> repository.sendPrompt(text) },
                    onDraftChange = { text -> repository.setDraft(text) },
                    onStop = { repository.abort() },
                    onRetry = { repository.retryLastTurn() },
                    onUndo = { repository.undoLastTurn() },
                    onRedo = { repository.redoLastTurn() },
                    onNewSession = {
                        // v9.28 (owner): sessions never leave the project branch.
                        githubClone.ensureProjectBranch(projectDir)
                        repository.newSession(null)
                    },
                    onOpenSessions = { route = ROUTE_SESSIONS },
                    onOpenProjects = {
                        projects = store.projects()
                        route = ROUTE_PROJECTS
                    },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                    // v9.1: provider management has its own surface behind the menu.
                    onOpenProviders = { route = ROUTE_PROVIDERS },
                    onOpenAddons = { route = ROUTE_ADDONS },
                    onOpenFiles = {
                        filesPath = ""
                        route = ROUTE_FILES
                    },
                    onOpenChanges = { route = ROUTE_CHANGES },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                    // v4 item 3: pick a starred model without leaving the chat.
                    onPickModel = { providerId, modelId -> repository.setModel(providerId, modelId) },
                    // Build/plan mode and the model's own thinking level, both
                    // sent with the next prompt (PromptInput.agent / .variant).
                    onPickMode = { mode -> repository.setAgentMode(mode) },
                    onPickThinking = { variant -> repository.setThinkingVariant(variant) },
                    // v9.19 token saver: upstream /compact - the server summarizes
                    // the history so later turns stop resending the whole transcript.
                    onCompactSession = { repository.compactSession() },
                    onPermissionReply = { id, reply -> repository.replyPermission(id, reply) },
                    onQuestionSubmit = { id, answers -> repository.replyQuestion(id, answers) },
                    onQuestionSkip = { id -> repository.rejectQuestion(id) },
                    onAttach = { picker.launch(ANY_CONTENT) },
                    onRemoveAttachment = { url -> repository.detach(url) },
                    onDismissBanner = { repository.clearBanner() },
                    // v9.35 (owner): the live preview is a chat surface now -
                    // the console sits under the TODO panel and the overlay
                    // morphs circle -> page over the conversation.
                    preview = ai.opencode.android.ui.chat.PreviewUi(
                        phase = previewPhase,
                        url = previewUrl,
                        port = previewPort,
                        reloadStamp = previewReload,
                        flashLabelRes = previewFlashRes,
                        // v9.38 (owner): the overlay's Stop key exists only
                        // when the shown port IS this project's in-process
                        // server - an honest button, not a wish.
                        canStop = previewPort > 0 && previewProjectDir?.let {
                            previewServers[it.absolutePath]
                        }?.let { it.running && it.port == previewPort } == true,
                    ),
                    // v9.37 (owner): a project's console shows only ITS ports -
                    // the ones owned by this project (a previous project's still
                    // running server is deliberately invisible here) plus the
                    // target this chat is already previewing.
                    livePorts = livePorts.filter { it == previewPort || portOwners[it] == projectName },
                    onOpenPreviewPort = { port ->
                        openChatPreview("http://127.0.0.1:$port/", port, projectDir)
                    },
                    onPreviewShow = {
                        if (previewUrl.isNotEmpty()) {
                            openChatPreview(previewUrl, previewPort, previewProjectDir)
                        }
                    },
                    onPreviewClose = { closeChatPreview() },
                    onPreviewReload = { previewReload = System.currentTimeMillis() },
                    onPreviewStop = {
                        val dir = previewProjectDir
                        scope.launch { stopProjectPreview(dir) }
                    },
                    onPreviewBrowser = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(previewUrl),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onPreviewCapture = { bitmap ->
                        val dir = projectDir
                        if (dir != null) {
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val folder = File(dir, ".preview")
                                        folder.mkdirs()
                                        File(folder, "latest.png").outputStream().use { out ->
                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                                        }
                                        // v9.36: the pane refresh also keeps
                                        // the agent's cheap view current.
                                        ai.opencode.android.preview.ShotStore.writeAgentJpeg(bitmap, folder)
                                    }
                                }
                                // Silent on purpose (v9.8): the snapshot is the
                                // AGENT'S eye, not a user event.
                                outcome.getOrNull()
                            }
                        }
                    },
                    onPreviewConsole = { line ->
                        val dir = previewProjectDir ?: projectDir
                        if (dir != null) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val folder = File(dir, ".preview")
                                        folder.mkdirs()
                                        val log = File(folder, "console.log")
                                        if (log.length() > 64_000) log.writeText("")
                                        log.appendText(line + "\n")
                                    }
                                }
                            }
                        }
                    },
                )
            }
            }
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
