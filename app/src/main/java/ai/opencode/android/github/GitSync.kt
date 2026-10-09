package ai.opencode.android.github

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * v9.27 - the app's own GitHub transport, on JGit (pure Java).
 *
 * WHY NOT THE BUNDLED GIT: libgit.so is built NO_CURL/NO_OPENSSL - "local
 * repo ops only" (ARCHITECTURE.md; phase4/scripts/10-build-payload.sh). It
 * CANNOT speak https: the owner's device clone died with "remote helper
 * 'https' aborted session" because git re-exec'd itself as the helper it
 * was built without. So the app clones and pushes with JGit over Android's
 * platform TLS, and the bundled git stays what it always was - the agent's
 * LOCAL tool (status/add/commit on the project branch).
 *
 * TOKEN RULE unchanged: read per call, passed only as a JGit credentials
 * object (x-access-token / <token> - GitHub's documented form for PATs over
 * HTTPS), never in a URL, never in argv, never persisted - the on-disk
 * origin URL stays token-free.
 */
object GitSync {

    /** GitHub's documented username for PAT-over-HTTPS basic auth. */
    private const val TOKEN_USER = "x-access-token"

    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider(TOKEN_USER, token)

    /** A [BatchingProgressMonitor] that forwards "Task: 42%" lines to [onLine]. */
    private fun monitor(onLine: (String) -> Unit) = object : BatchingProgressMonitor() {
        override fun onUpdate(taskName: String, workCurr: Int, duration: java.time.Duration) {
            onLine("$taskName: $workCurr")
        }
        override fun onUpdate(
            taskName: String,
            workCurr: Int,
            workTotal: Int,
            percentDone: Int,
            duration: java.time.Duration,
        ) {
            onLine("$taskName: $percentDone% ($workCurr/$workTotal)")
        }
        override fun onEndTask(taskName: String, workCurr: Int, duration: java.time.Duration) {}
        override fun onEndTask(
            taskName: String,
            workCurr: Int,
            workTotal: Int,
            percentDone: Int,
            duration: java.time.Duration,
        ) {}
    }

    /**
     * Clone [cloneUrl] into [targetDir] and leave the checkout on a fresh
     * [branch]. The directory already exists (ProjectStore created it empty);
     * JGit accepts an existing empty dir.
     */
    fun cloneAndBranch(
        cloneUrl: String,
        targetDir: File,
        branch: String,
        token: String,
        onLine: (String) -> Unit,
    ) {
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(targetDir)
            .setCredentialsProvider(credentials(token))
            .setProgressMonitor(monitor(onLine))
            .call()
            .use { git ->
                git.checkout().setCreateBranch(true).setName(branch).call()
            }
    }

    /**
     * v9.28 (owner): a GitHub project is ALWAYS on its own branch - every
     * session works on `opencode/<project>`. If something (a model command,
     * a crash mid-rebase) left the checkout elsewhere, this puts it back;
     * a dirty-tree conflict aborts the checkout harmlessly (JGit throws,
     * we swallow - never destroy uncommitted work to enforce a branch).
     */
    fun ensureProjectBranch(dir: File?) {
        if (dir == null || !File(dir, ".git").isDirectory) return
        runCatching {
            Git.open(dir).use { git ->
                val want = GitClone.projectBranch(dir.name)
                if (git.repository.branch == want) return
                val exists = git.repository.resolve("refs/heads/$want") != null
                git.checkout().setName(want).setCreateBranch(!exists).call()
            }
        }
    }

    /** True when [dir] is a git work tree whose `origin` points at GitHub. */
    fun isGithubProject(dir: File?): Boolean {
        if (dir == null || !File(dir, ".git").isDirectory) return false
        return runCatching {
            Git.open(dir).use { git ->
                val url = git.repository.config.getString("remote", "origin", "url")
                url != null && url.contains("github.com")
            }
        }.getOrDefault(false)
    }

    // v9.29 (owner): pushCurrentBranch / needsPush REMOVED. JGit now only
    // CLONES (device-proven); pushing is the model's job via the bundled
    // https-capable git - see the environment brief's GitHub section.
}
