package ai.opencode.android.github

import java.io.File

/**
 * v9.25: argv/env construction for the app-side `git` runs (clone, branch).
 * Pure functions, JVM-pinned, because the security property lives here:
 *
 *  THE TOKEN NEVER TOUCHES ARGV AND NEVER PERSISTS ON DISK. It rides in the
 *  process ENVIRONMENT as a one-shot `http.extraHeader` (GIT_CONFIG_* env,
 *  git 2.31+; the bundled git is 2.48) - so `.git/config` keeps a clean,
 *  token-free remote URL, and the agent pushes exactly the way the brief
 *  already teaches: `https://x-access-token:$key@github.com/...`.
 *
 * The git binary and the env mirror what the runtime's own children use
 * (same bin symlinks, same HOME) - the exact setup whose clones the owner's
 * device has already proven in the field reports.
 */
object GitClone {

    /**
     * Base env for an app-side git process; mirrors RuntimeEnv's choices.
     *
     * v9.26 (owner device, "remote helper 'https' aborted session"): the env
     * must ALSO carry the seccomp shim as LD_PRELOAD. The opencode server
     * tree gets it from the exec shim, which is why the MODEL's git pushes
     * work on the device - but a bare app-side exec of git spawned a
     * `git-remote-https` helper with no shim, and Android's seccomp filter
     * killed it mid-session. Same XDG dirs as the runtime too, so git reads
     * exactly the config the field-proven runs read.
     */
    fun baseEnv(
        home: File,
        binDir: File,
        tmp: File,
        xdgConfig: File? = null,
        xdgData: File? = null,
        xdgCache: File? = null,
        seccompShim: File? = null,
    ): Map<String, String> = buildMap {
        put("HOME", home.absolutePath)
        put("PATH", binDir.absolutePath + ":/system/bin:/system/xbin")
        put("TMPDIR", tmp.absolutePath)
        put("LANG", "C.UTF-8")
        put("SHELL", "/system/bin/sh")
        // Never let a git subprocess sit waiting for a terminal that is not there.
        put("GIT_TERMINAL_PROMPT", "0")
        if (xdgConfig != null) put("XDG_CONFIG_HOME", xdgConfig.absolutePath)
        if (xdgData != null) put("XDG_DATA_HOME", xdgData.absolutePath)
        if (xdgCache != null) put("XDG_CACHE_HOME", xdgCache.absolutePath)
        if (seccompShim != null) put("LD_PRELOAD", seccompShim.absolutePath)
    }

    /** One-shot auth: Basic x-access-token:<token>, via env - never argv. */
    fun authEnv(token: String): Map<String, String> {
        // java.util.Base64 (API 26+; minSdk is 29) - JVM-testable, unlike android.util.
        val basic = java.util.Base64.getEncoder()
            .encodeToString("x-access-token:$token".toByteArray(Charsets.UTF_8))
        return mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
        )
    }

    fun cloneCommand(git: File, cloneUrl: String, targetDir: File): List<String> =
        listOf(git.absolutePath, "clone", "--progress", cloneUrl, targetDir.absolutePath)

    fun branchCommand(git: File, branch: String): List<String> =
        listOf(git.absolutePath, "checkout", "-b", branch)

    /**
     * v9.26 (owner): ONE branch per project - `opencode/<project>` - created
     * at clone time; every chat of that project works on it, so the whole
     * project is followable on GitHub (the sandbox is just where it runs).
     * Replaces the v9.25 per-chat `opencode/chat-<id>` scheme.
     */
    fun projectBranch(project: String): String = "opencode/" + project.lowercase()

    /**
     * Run one git command to completion. Returns exit code; streams each
     * output line to [onLine] (progress for the UI, never logged with env).
     */
    fun run(
        command: List<String>,
        env: Map<String, String>,
        workDir: File?,
        onLine: (String) -> Unit = {},
    ): Int {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (workDir != null) pb.directory(workDir)
        pb.environment().putAll(env)
        val proc = pb.start()
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) onLine(line.trim())
            }
            return proc.waitFor()
        } finally {
            runCatching { proc.destroy() }
        }
    }
}
