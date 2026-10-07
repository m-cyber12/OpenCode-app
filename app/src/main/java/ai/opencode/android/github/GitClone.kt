package ai.opencode.android.github

import java.io.File
import java.security.SecureRandom

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

    /** Base env for an app-side git process; mirrors RuntimeEnv's choices. */
    fun baseEnv(home: File, binDir: File, tmp: File): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "PATH" to binDir.absolutePath + ":/system/bin:/system/xbin",
        "TMPDIR" to tmp.absolutePath,
        "LANG" to "C.UTF-8",
        "SHELL" to "/system/bin/sh",
    )

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

    /** The owner's chosen scheme: `opencode/chat-<short-id>`, like Arena's. */
    fun branchName(chatId: String): String = "opencode/chat-$chatId"

    private val random = SecureRandom()

    /** Six lowercase hex chars - short enough to read, unique enough per repo. */
    fun newChatId(): String = buildString {
        repeat(6) { append("0123456789abcdef"[random.nextInt(16)]) }
    }

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
