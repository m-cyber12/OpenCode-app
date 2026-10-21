package ai.opencode.android.ui.common

/**
 * v9.41 (owner item 4): the machinery the user should never have to see.
 *
 * The app maintains the project-root `AGENTS.md` itself (the GitHub workspace
 * block + the project instructions OpenCode loads). A user who opens the
 * Sandbox or the Diff page mid-startup and finds "AGENTS.md" being written
 * reads it as "the agent is uploading my repository" - it is not; it is the
 * environment keeping its own notes. The file stays fully real on disk (the
 * model reads it; nothing about behavior changes) - it is simply not user
 * surface. Dot-directories (.git, .opencode, .preview, caches) follow the
 * same rule: a file manager shows them, so the listing shows the project,
 * not the plumbing, by hiding names that start with "." at the listing layer.
 */
object HiddenFromUser {

    /** Hidden in the Files listing: dot-entries and the app-maintained root AGENTS.md. */
    fun isListingHidden(name: String): Boolean =
        name.startsWith(".") || isAgentsFile(name)

    /** True for the root-level AGENTS.md by name. */
    fun isAgentsFile(name: String): Boolean =
        name.equals("AGENTS.md", ignoreCase = true)

    /** Hidden in the Diff / changes view: the root AGENTS.md (any depth marker "./" tolerated). */
    fun isDiffHidden(relPath: String): Boolean {
        val p = relPath.removePrefix("./").removePrefix("/")
        return p.equals("AGENTS.md", ignoreCase = true)
    }
}
