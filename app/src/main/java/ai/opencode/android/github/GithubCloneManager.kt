package ai.opencode.android.github

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v9.25: the I/O half of the Arena-style repo UX (pure half: [GithubRepos],
 * [GitClone]). Lists the user's repositories and clones one into a fresh
 * project directory BEFORE the chat starts, then puts it on its own
 * `opencode/chat-<id>` branch - the same order of events the Arena platform
 * gives its agents (checkout and branch exist before the first command).
 *
 * The token is read per call and handed only to [GitClone.authEnv]; it is
 * never stored here, never in a URL, never in argv, never in state.
 */
class GithubCloneManager(
    private val apiBase: String,
    private val webBase: String,
    private val git: () -> File,
    private val baseEnv: () -> Map<String, String>,
) {

    data class State(
        val phase: Phase = Phase.IDLE,
        val repos: List<GithubRepos.Repo> = emptyList(),
        /** Last progress line from git, for the UI ("Receiving objects: 42%"). */
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
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Clone [repo] into [targetDir] (an EMPTY directory the project store
     * just created) and create the chat branch. [onDone] fires with the
     * branch name on success.
     */
    fun clone(repo: GithubRepos.Repo, token: String, targetDir: File, onDone: (String) -> Unit) {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(
            phase = Phase.CLONING, project = targetDir.name, error = "", progress = "",
        )
        job = scope.launch {
            try {
                val env = baseEnv() + GitClone.authEnv(token)
                val rcClone = GitClone.run(
                    GitClone.cloneCommand(git(), GithubRepos.cloneUrl(webBase, repo.fullName), targetDir),
                    env,
                    targetDir.parentFile,
                ) { line -> _state.value = _state.value.copy(progress = line) }
                if (rcClone != 0) error("git clone exited with $rcClone (${_state.value.progress})")
                val branch = GitClone.branchName(GitClone.newChatId())
                // Branch creation is local-only; no auth env needed.
                val rcBranch = GitClone.run(GitClone.branchCommand(git(), branch), baseEnv(), targetDir)
                if (rcBranch != 0) error("git checkout -b exited with $rcBranch")
                _state.value = _state.value.copy(phase = Phase.DONE, progress = branch)
                onDone(branch)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    /** New chat in an app-cloned (or any git) project = new branch, like Arena. */
    fun branchForNewChat(projectDir: File?) {
        if (projectDir == null || !File(projectDir, ".git").isDirectory) return
        scope.launch {
            runCatching {
                GitClone.run(
                    GitClone.branchCommand(git(), GitClone.branchName(GitClone.newChatId())),
                    baseEnv(),
                    projectDir,
                )
            }
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
