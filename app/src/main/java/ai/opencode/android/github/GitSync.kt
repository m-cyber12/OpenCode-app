package ai.opencode.android.github

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.transport.RemoteRefUpdate
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

    /**
     * How many commits the current branch is AHEAD of its upstream; 0 when
     * in sync, and ALSO when the branch has no upstream yet (first push) -
     * callers that need "is there anything to push" should use [needsPush].
     */
    private fun aheadCount(git: Git): Int? {
        val branch = git.repository.branch ?: return null
        return BranchTrackingStatus.of(git.repository, branch)?.aheadCount
    }

    /**
     * True when pushing would move the remote: the branch has local commits
     * its upstream lacks, or it has never been pushed at all (no upstream).
     */
    fun needsPush(dir: File): Boolean = runCatching {
        Git.open(dir).use { git ->
            if (git.repository.resolve("HEAD") == null) return@use false // empty repo
            val ahead = aheadCount(git)
            ahead == null || ahead > 0
        }
    }.getOrDefault(false)

    /**
     * Push the CURRENT branch to origin under its own name, creating it
     * remotely on first push and recording the upstream so [needsPush] can
     * answer cheaply next time. Returns a one-line human summary.
     * Throws on transport/auth failures - callers surface the message.
     */
    fun pushCurrentBranch(dir: File, token: String, onLine: (String) -> Unit = {}): String {
        Git.open(dir).use { git ->
            val branch = git.repository.branch ?: error("no current branch")
            val results = git.push()
                .setRemote("origin")
                .add(branch)
                .setCredentialsProvider(credentials(token))
                .setProgressMonitor(monitor(onLine))
                .call()
            // Record branch.<name>.remote/merge once, so tracking status works.
            val cfg = git.repository.config
            if (cfg.getString("branch", branch, "remote") == null) {
                cfg.setString("branch", branch, "remote", "origin")
                cfg.setString("branch", branch, "merge", "refs/heads/$branch")
                cfg.save()
            }
            for (result in results) {
                for (update in result.remoteUpdates) {
                    when (update.status) {
                        RemoteRefUpdate.Status.OK -> return "pushed $branch"
                        RemoteRefUpdate.Status.UP_TO_DATE -> return "up to date"
                        else -> error("push $branch: ${update.status}${update.message?.let { " - $it" } ?: ""}")
                    }
                }
            }
            return "nothing to push"
        }
    }
}
