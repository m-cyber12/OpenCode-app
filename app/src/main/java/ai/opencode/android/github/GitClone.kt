package ai.opencode.android.github

/**
 * v9.26/v9.27: the branch-naming rule for app-cloned projects. The exec-git
 * plumbing that used to live here (argv/env builders, GIT_CONFIG_* auth,
 * LD_PRELOAD mirroring) is GONE: the bundled libgit.so is built
 * NO_CURL/NO_OPENSSL and can never speak https, so the app's transport is
 * JGit ([GitSync]) and nothing app-side execs git anymore.
 */
object GitClone {

    /**
     * ONE branch per project - `opencode/<project>` (lowercased) - created
     * at clone time; every chat of the project works on it, so the whole
     * project is followable on GitHub (the sandbox is just where it runs).
     */
    fun projectBranch(project: String): String = "opencode/" + project.lowercase()
}
