package ai.opencode.android.github

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v9.25/v9.26/v9.27: the I/O half of the GitHub project UX (pure half:
 * [GithubRepos]; transport: [GitSync] on JGit). Lists the user's
 * repositories, clones one into a fresh project directory BEFORE any agent
 * runs, parks it on the project's own `opencode/<project>` branch - and
 * AUTO-PUSHES that branch after each completed agent reply (owner's v9.27
 * decision), so the project's progress lives on GitHub, not in the sandbox.
 *
 * The token is read per call and handed only to [GitSync]; it is never
 * stored here, never in a URL, never in argv, never in state.
 */
class GithubCloneManager(
    private val apiBase: String,
    private val webBase: String,
) {

    data class State(
        val phase: Phase = Phase.IDLE,
        val repos: List<GithubRepos.Repo> = emptyList(),
        /** Last progress line for the UI ("Receiving objects: 42% ..."). */
        val progress: String = "",
        /** Project name being cloned / just cloned (phase tells which). */
        val project: String = "",
        val error: String = "",
    )

    enum class Phase { IDLE, LISTING, CLONING, DONE, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun listRepos(token: String) {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(phase = Phase.LISTING, error = "", progress = "")
        job = scope.launch {
            try {
                val json = fetch(GithubRepos.listUrl(apiBase), GithubRepos.headers(token))
                _state.value = _state.value.copy(phase = Phase.IDLE, repos = GithubRepos.parseRepos(json))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = ai.opencode.android.client.AccountRedaction.scrub(t.message ?: t.javaClass.simpleName),
                )
            }
        }
    }

    /**
     * Clone [repo] into [targetDir] (an EMPTY directory the project store
     * just created) and create the project branch. [onDone] fires with the
     * branch name on success.
     */
    fun clone(repo: GithubRepos.Repo, token: String, targetDir: File, onDone: (String) -> Unit) {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(
            phase = Phase.CLONING, project = targetDir.name, error = "", progress = "",
        )
        job = scope.launch {
            try {
                // v9.26 (owner): ONE branch per project, created here, worked
                // on by every chat of the project.
                val branch = GitClone.projectBranch(targetDir.name)
                GitSync.cloneAndBranch(
                    cloneUrl = GithubRepos.cloneUrl(webBase, repo.fullName),
                    targetDir = targetDir,
                    branch = branch,
                    token = token,
                ) { line -> _state.value = _state.value.copy(progress = line) }
                _state.value = _state.value.copy(phase = Phase.DONE, progress = branch)
                onDone(branch)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = ai.opencode.android.client.AccountRedaction.scrub(t.message ?: t.javaClass.simpleName),
                )
            }
        }
    }

    // v9.29 (owner): the app-side auto-push is GONE. The owner's device pass
    // proved the bundled git speaks https, so the MODEL pushes its own work
    // (the environment brief carries the x-access-token URL recipe). The
    // JGit-based autoPush had failed silently on device - its invisible
    // pushNote was exactly the weakness §B.49 flagged.

    /**
     * v9.28 (owner): every session of a GitHub project stays on the project
     * branch. Called at project open and at each new-session tap; no-op for
     * non-git projects, never destructive (see [GitSync.ensureProjectBranch]).
     */
    fun ensureProjectBranch(projectDir: File?) {
        scope.launch { GitSync.ensureProjectBranch(projectDir) }
    }

    /**
     * v9.41 (owner item 3): make the checkout REAL, then FRESH, synchronously
     * for the calling coroutine - the pre-prompt gate for GitHub projects.
     * First prompt of a fresh GitHub project clones; every prompt after that
     * fetches and fast-forwards (see [GitSync.refreshFastForward]). A failure
     * never blocks the send: the state carries the error for the UI, the
     * prompt proceeds with what is on disk. No token, or a project that was
     * never created from a repo: an instant no-op.
     */
    suspend fun materialize(context: android.content.Context, project: String, projectDir: File?, token: String?) {
        val dir = projectDir ?: return
        if (token.isNullOrEmpty()) return
        // ONLY projects this app created around a GitHub repository (the filesDir
        // marker). A local/Imported project may be the user's own checkout with
        // their own remote - pulling into that behind their back is not this
        // app's business, and the marker is the consent boundary.
        val meta = withContext(Dispatchers.IO) {
            ai.opencode.android.github.GithubProjectMeta.read(context, project)
        }
        if (meta == null) return
        _state.value = _state.value.copy(
            phase = Phase.CLONING, project = project, error = "", progress = "",
        )
        try {
            withContext(Dispatchers.IO) {
                if (!File(dir, ".git").isDirectory && meta != null) {
                    _state.value = _state.value.copy(progress = "cloning ${meta.repo.substringAfterLast('/')}")
                    GitSync.cloneOnly(
                        cloneUrl = GithubRepos.cloneUrl(webBase, meta.repo),
                        targetDir = dir,
                        token = token,
                    ) { line -> _state.value = _state.value.copy(progress = line) }
                } else if (File(dir, ".git").isDirectory) {
                    _state.value = _state.value.copy(progress = "refreshing")
                    GitSync.refreshFastForward(dir, token) { line ->
                        _state.value = _state.value.copy(progress = line)
                    }
                }
                GitSync.ensureProjectBranch(dir)
            }
            _state.value = _state.value.copy(phase = Phase.DONE, progress = "")
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                phase = Phase.ERROR, progress = "",
                error = ai.opencode.android.client.AccountRedaction.scrub(t.message ?: t.javaClass.simpleName),
            )
        }
    }

    fun dismissError() {
        if (_state.value.phase == Phase.ERROR || _state.value.phase == Phase.DONE) {
            _state.value = _state.value.copy(phase = Phase.IDLE, error = "")
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode != 200) error("GitHub answered HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
