package ai.opencode.android.runtime

import java.io.File

/**
 * v9.38 (owner): "the GitHub repository is the primary workspace; the sandbox
 * is temporary. Even if the model creates something in response to the very
 * first prompt, it should push." Prose in the global brief proved skippable
 * (the owner's device run: the model behaved like a plain project until it was
 * told otherwise), so the rule now also rides where upstream auto-loads
 * project instructions: the project-root `AGENTS.md`.
 *
 * The write is additive and idempotent: a block between two marker lines is
 * created (no file yet), replaced between the markers (our block already
 * there) or appended (a real, user-written AGENTS.md keeps every byte of its
 * own content). The block text is fixed at write time - the app process never
 * holds the `key` env value (that lives in the agent's environment), so the
 * block points at the brief instead of restating a token form it cannot
 * verify.
 */
object GithubWorkspaceNotes {

    internal const val BEGIN = "<!-- opencode:github-workspace (written by the app; edit freely OUTSIDE this block) -->"
    internal const val END = "<!-- opencode:github-workspace:end -->"

    internal const val BLOCK = """## GitHub workspace rules (from the OpenCode Android app)

This project IS a GitHub working copy, and the remote repository is its
primary workspace - this device folder is a temporary place to work.
A change that is not pushed does not, for practical purposes, exist.

- Commit AND push after EVERY logical change, starting with the very first
  one, even an edit made in response to the user's opening prompt. Never
  wait to be asked, never batch work "until the end".
- Work on the project branch the app checked out (`opencode/<project folder>`,
  lower-case). Before your FIRST commit run `git rev-parse --abbrev-ref HEAD`:
  if the checkout sits on the repo's default branch (master or main), move
  the work onto the project branch first - `git checkout -B opencode/<project>`
  carries your commits and uncommitted edits with it.
  NEVER push master or main; no other branches unless the user asks.
- Push with the token URL the environment brief's GitHub section provides;
  `git remote get-url origin` names the owner/repo. Never print the token,
  never commit it into any file.
- Anything else the user asks about this repo happens ON GitHub - the repo,
  not only the sandbox."""

    /**
     * Ensure [dir]/AGENTS.md carries the block. Returns true when the file
     * changed, false when it was already current. Safe to call from any
     * thread; no Android deps; every IO failure is swallowed (the notes are
     * a bonus channel, never a blocker).
     */
    fun ensure(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val file = File(dir, "AGENTS.md")
        val wanted = BEGIN + "\n" + BLOCK + "\n" + END + "\n"
        return runCatching {
            val existing = if (file.isFile) file.readText() else null
            when {
                existing == null -> {
                    file.writeText(
                        "# Project notes (AGENTS.md)\n\n" +
                            "This file is auto-loaded into the agent's context for this project.\n\n" +
                            wanted,
                    )
                    true
                }
                existing.contains(BEGIN) -> {
                    val b = existing.indexOf(BEGIN)
                    val e = existing.indexOf(END, b)
                    if (e < 0) false else {
                        val updated = existing.substring(0, b) + wanted +
                            existing.substring(e + END.length).removePrefix("\n")
                        if (updated == existing) false else {
                            file.writeText(updated)
                            true
                        }
                    }
                }
                else -> {
                    file.writeText(existing.trimEnd() + "\n\n" + wanted)
                    true
                }
            }
        }.getOrDefault(false)
    }
}
