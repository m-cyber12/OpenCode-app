package ai.opencode.android.github

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS

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

    /** Name of the helper script, kept inside the checkout's own `.git` directory. */
    internal const val CREDENTIAL_SCRIPT = "opencode-credential.sh"

    /**
     * The credential helper script. It answers `get` with the token that is already in the
     * agent's environment as `key`, so the token is never written to disk and `origin` stays
     * token-free. A script file (not an inline shell string) keeps the git config value free
     * of quotes and `;`, which git would otherwise read as a comment. Before this, a plain
     * `git push` in the agent's shell had no credentials and failed with "could not read
     * Username" (owner, 2026-11-23).
     */
    internal val CREDENTIAL_SCRIPT_BODY: String =
        "#!/bin/sh\n" +
            "# Written by the OpenCode Android app. Answers git with the GitHub token from the environment.\n" +
            "if [ \"\$1\" = get ]; then\n" +
            "  printf 'username=%s\\n' " + TOKEN_USER + "\n" +
            "  printf 'password=%s\\n' \"${'$'}key\"\n" +
            "fi\n"

    /**
     * True when the remote has no branch at all - a freshly created GitHub repository with
     * no commits. An unreachable remote throws, so an auth failure is not mistaken for "empty".
     */
    fun isEmptyRemote(cloneUrl: String, token: String): Boolean =
        Git.lsRemoteRepository()
            .setRemote(cloneUrl)
            .setCredentialsProvider(credentials(token))
            .setHeads(true)
            .setTags(true)
            .call()
            .isEmpty()

    /**
     * Makes a GitHub checkout ready for the agent to commit and push, on the repository's
     * own config so it survives restarts: the credential helper above, and - only when the
     * git identity is not set yet - the account's name and GitHub no-reply email. The agent
     * then commits as the owner without asking for an ID or email. A null [viewer] (the
     * account lookup failed) sets the helper and leaves the identity for the next prompt.
     */
    fun configureAgent(dir: File?, webBase: String, viewer: ai.opencode.android.github.GithubRepos.Viewer?) {
        if (dir == null || !File(dir, ".git").isDirectory) return
        runCatching {
            Git.open(dir).use { git ->
                val config = localConfig(git)
                val script = File(git.repository.directory, CREDENTIAL_SCRIPT)
                script.writeText(CREDENTIAL_SCRIPT_BODY)
                script.setExecutable(true, true)
                // The helper is scoped to the GitHub web origin (from strings.xml), not to every host.
                config.setString(
                    "credential",
                    webBase.trimEnd('/'),
                    "helper",
                    "!sh " + script.absolutePath,
                )
                if (viewer != null && config.getString("user", null, "name") == null) {
                    config.setString("user", null, "name", viewer.login)
                    config.setString("user", null, "email", viewer.noReplyEmail)
                }
                config.save()
            }
        }
    }

    /** True when the checkout has no git identity yet (see [configureAgent]). */
    fun identityMissing(dir: File?): Boolean {
        if (dir == null || !File(dir, ".git").isDirectory) return false
        return runCatching {
            Git.open(dir).use { localConfig(it).getString("user", null, "name") == null }
        }.getOrDefault(false)
    }

    /**
     * The checkout's OWN `.git/config` only. JGit's repository config also layers the
     * machine's global git config underneath, which would make the identity check depend on
     * whatever the device or CI happens to have set globally.
     */
    private fun localConfig(git: Git): FileBasedConfig =
        FileBasedConfig(File(git.repository.directory, "config"), FS.DETECTED).also { it.load() }

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
        if (hasContent(targetDir)) {
            initIntoExisting(cloneUrl, targetDir, token, branch, onLine)
            return
        }
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

    /** True when [dir] exists and holds at least one entry. */
    private fun hasContent(dir: File): Boolean = dir.listFiles()?.isNotEmpty() == true

    /**
     * Clone INTO a folder that already has files in it (owner: "Destination path
     * already exists and is not an empty directory"). A GitHub project's folder
     * can hold files the app wrote before the first clone (its AGENTS.md notes),
     * and the owner's own files must survive. So: `git init` in place, point
     * `origin` at the repository, fetch, then check out the remote's default
     * branch as a new local branch ([branch], or the default's own name when
     * null). Untracked files the repository does not contain stay exactly as
     * they are. A file the repository DOES contain blocks the checkout with
     * JGit's conflict error and nothing is overwritten. If this call created the
     * `.git` and the attempt then fails, that `.git` is removed again, so a
     * failed attempt never leaves a half-made repository behind.
     */
    private fun initIntoExisting(
        cloneUrl: String,
        targetDir: File,
        token: String,
        branch: String?,
        onLine: (String) -> Unit,
    ): String {
        val createdGit = !File(targetDir, ".git").exists()
        try {
            Git.init().setDirectory(targetDir).call().use { git ->
                val config = git.repository.config
                config.setString("remote", "origin", "url", cloneUrl)
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                config.save()
                val fetched = git.fetch()
                    .setRemote("origin")
                    .setCredentialsProvider(credentials(token))
                    .setProgressMonitor(monitor(onLine))
                    .call()
                val advertised = fetched.getAdvertisedRef(Constants.HEAD)?.target?.name.orEmpty()
                val remoteDefault = advertised.takeIf { it.startsWith("refs/heads/") }
                    ?.removePrefix("refs/heads/")
                    ?: "main"
                if (git.repository.resolve("refs/remotes/origin/$remoteDefault") == null) {
                    error("The repository has no branch named $remoteDefault to check out.")
                }
                val local = branch ?: remoteDefault
                git.checkout()
                    .setCreateBranch(true)
                    .setName(local)
                    .setStartPoint("origin/$remoteDefault")
                    .call()
                return local
            }
        } catch (t: Throwable) {
            if (createdGit) File(targetDir, ".git").deleteRecursively()
            if (t is org.eclipse.jgit.api.errors.CheckoutConflictException) {
                throw IllegalStateException(
                    "This folder already has files that the repository also contains, so nothing was " +
                        "overwritten. Move those files out and clone again. (${t.message})",
                    t,
                )
            }
            throw t
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
     * v9.41 (owner item 3): clone WITHOUT creating any branch - the first
     * prompt sees the repository exactly as GitHub serves it (its default
     * branch); [ensureProjectBranch] immediately after parks the checkout on
     * `opencode/<project>` in the same motion every later prompt performs.
     */
    fun cloneOnly(cloneUrl: String, targetDir: File, token: String, onLine: (String) -> Unit) {
        if (hasContent(targetDir)) {
            initIntoExisting(cloneUrl, targetDir, token, null, onLine)
            return
        }
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(targetDir)
            .setCredentialsProvider(credentials(token))
            .setProgressMonitor(monitor(onLine))
            .call()
            .use { }
    }

    /**
     * 2026-11-23 (owner): the project branch goes to GitHub right after the first clone,
     * so the models work on it and the refresh before each prompt has it to
     * fast-forward from. Only the project branch is pushed - never the default branch.
     * Skipped when origin already has the branch (a tracking ref exists after the
     * fetch). A rejected push is not an exception; the next prompt tries again.
     */
    fun publishProjectBranch(dir: File?, token: String, onLine: (String) -> Unit) {
        if (dir == null || !File(dir, ".git").isDirectory) return
        runCatching {
            Git.open(dir).use { git ->
                val want = GitClone.projectBranch(dir.name)
                if (git.repository.branch != want) return
                if (git.repository.resolve("refs/remotes/origin/$want") != null) return
                git.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/$want:refs/heads/$want"))
                    .setCredentialsProvider(credentials(token))
                    .setProgressMonitor(monitor(onLine))
                    .call()
            }
        }
    }

    /**
     * v9.41 (owner item 3): "the repository is always fresh when the model
     * touches it." Fetch origin and fast-forward the CURRENT branch when -
     * and only when - it can be done without touching a single local byte:
     * uncommitted tracked work is never disturbed, a diverged branch is
     * never rebased or reset, and every failure here is a deliberate no-op -
     * the prompt proceeds with what is already on disk. (The model commits
     * after every change - the AGENTS block rule - so the common state IS a
     * clean tree, and the fast-forward lands.)
     */
    fun refreshFastForward(dir: File?, token: String, onLine: (String) -> Unit) {
        if (dir == null || !File(dir, ".git").isDirectory) return
        runCatching {
            Git.open(dir).use { git ->
                val branch = git.repository.branch ?: return
                git.fetch().setRemote("origin").setCredentialsProvider(credentials(token))
                    .setProgressMonitor(monitor(onLine)).call()
                val tracked = git.repository.resolve("refs/remotes/origin/$branch") ?: return
                if (git.repository.resolve("refs/heads/$branch") == tracked) return
                if (git.status().call().hasUncommittedChanges()) return
                runCatching {
                    git.merge()
                        .include(tracked)
                        .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.FF_ONLY)
                        .call()
                }
            }
        }
    }

    /** The `owner/name` behind a cloned project's origin URL, or "" (item 13). */
    fun originRepo(dir: File?): String {
        if (dir == null || !File(dir, ".git").isDirectory) return ""
        return runCatching {
            Git.open(dir).use { git ->
                val url = git.repository.config.getString("remote", "origin", "url") ?: return ""
                val tail = url.substringAfterLast('/')
                val name = tail.removeSuffix(".git")
                val owner = url.substringBeforeLast('/').substringAfterLast(':').removePrefix("//")
                    .substringAfterLast('/')
                if (owner.isNotEmpty() && name.isNotEmpty()) "$owner/$name" else name
            }
        }.getOrDefault("")
    }

    /** The branch the checkout currently sits on, "" when there is none (item 13). */
    fun currentBranch(dir: File?): String {
        if (dir == null || !File(dir, ".git").isDirectory) return ""
        return runCatching { Git.open(dir).use { it.repository.branch ?: "" } }.getOrDefault("")
    }

    // v9.29 (owner): pushCurrentBranch / needsPush REMOVED. JGit now only
    // CLONES (device-proven); pushing is the model's job via the bundled
    // https-capable git - see the environment brief's GitHub section.
    // v9.41 (owner item 3): fetch/refresh was ADDED back on the same JGit
    // transport - the clone-on-prompt + refresh-on-every-prompt rule needs an
    // app-side network operation, and only JGit speaks https on device.
}
