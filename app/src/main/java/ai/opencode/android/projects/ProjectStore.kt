package ai.opencode.android.projects

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * The list of on-device project directories the agent can work in.
 *
 * A "project" is nothing more than a directory under the app's project root that
 * is used as the OpenCode instance directory (`OpenCodeApi.directory`, which
 * upstream resolves per request). The server owns everything else about a
 * session; this store only remembers which directories exist and which one the
 * user last opened, because the choice has to survive a process restart and the
 * user must never be asked for a path.
 *
 * WHERE THE ROOT IS, and why that is a product question rather than a detail:
 *
 *  * **default, Phase 10 continuation v3** — `Documents/OpenCode/<project>` on
 *    shared storage. Any file manager, the Files app, MTP and `adb` show the
 *    project files live, from the moment they are created, with no export step.
 *    Writing there needs All files access (`MANAGE_EXTERNAL_STORAGE`) on
 *    Android 11+; the app asks for it in its own storage panel and keeps the
 *    previous root until it is granted. See [StorageChoice] and [RuntimePaths].
 *  * **chosen** — a folder the user picked through SAF, when it resolves to a real
 *    path on primary storage (SD cards and cloud providers cannot be handed to a
 *    POSIX runtime, so they are refused with an explanation rather than accepted
 *    and silently broken).
 *  * **fallbacks** — `Android/data/<applicationId>/files/workspaces` (no
 *    permission, `adb`-visible, but file managers cannot browse it on Android
 *    11+) and, when there is no usable external storage at all, app-private
 *    `files/workspaces`.
 *
 * The promise that matters to the user is: **the live project directory is a
 * normal folder they can open**, and where it is lives in [RuntimePaths.mode]
 * rather than in a comment, so the UI states it and the device gates assert on
 * it. The fallbacks exist because Android can refuse the permission, and the app
 * says so plainly instead of pretending the files are reachable when they are
 * not.
 *
 * Phase 6 kept this to list, create, open. Phase 7 adds the rest of workspace
 * management - rename, delete, and adopt (register a tree that an import copied
 * into place). Session scoping is the server's: sessions are keyed by the project
 * directory, so renaming a project's folder re-points future sessions while the
 * old sessions stay attached to their recorded path (the same way renaming a
 * folder on disk behaves for a desktop OpenCode install). Deleting a project also
 * asks the repository to delete that project's sessions.
 */
data class Project(
    val name: String,
    val dir: File,
    val createdMs: Long,
    val lastOpenedMs: Long,
) {
    val path: String get() = dir.absolutePath
}

class ProjectStore internal constructor(
    private val root: File,
    private val prefs: SharedPreferences,
    /**
     * Older project roots this install may have used, in preference order (newest
     * first): `Android/data/<pkg>/files/workspaces` and, before that,
     * app-private `files/workspaces`. Empty for a fresh install.
     *
     * Used only by [ensureMigrated]; a root that is the current root is ignored, so
     * the caller can pass every candidate without checking.
     */
    private val legacyRoots: List<File> = emptyList(),
) {

    /** Convenience for the single-legacy-root case (unit tests, older callers). */
    internal constructor(root: File, prefs: SharedPreferences, legacyRoot: File?) :
        this(root, prefs, listOfNotNull(legacyRoot))

    /**
     * Every project directory that exists, most recently opened first.
     *
     * Directories are discovered from the filesystem rather than from a written
     * index, so a project created by an earlier phase of this app (Phase 5 used a
     * single `mobile` workspace) or by a previous install of the same data
     * directory shows up instead of being orphaned.
     */
    fun projects(): List<Project> {
        val dirs = root.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        return dirs
            .map { d ->
                Project(
                    name = d.name,
                    dir = d,
                    createdMs = prefs.getLong(createdKey(d.name), d.lastModified()),
                    lastOpenedMs = prefs.getLong(openedKey(d.name), 0L),
                )
            }
            .sortedWith(compareByDescending<Project> { it.lastOpenedMs }.thenBy { it.name })
    }

    /** The project the user last opened, or null when none exists yet. */
    fun active(): Project? {
        val name = prefs.getString(KEY_ACTIVE, "") ?: ""
        val all = projects()
        return all.firstOrNull { it.name == name } ?: all.firstOrNull()
    }

    fun activeName(): String = active()?.name ?: ""

    /**
     * Create (or adopt) a project directory from a name the user typed.
     *
     * Names are restricted to filesystem-safe characters because the directory
     * becomes a working directory for shell commands the agent runs; a name with
     * a space or a quote in it would be a footgun the app does not need. Colliding
     * names get a numeric suffix rather than reusing the existing directory, so
     * "create" never silently opens somebody else's files.
     */
    fun create(rawName: String): Project {
        val base = sanitize(rawName).ifEmpty { DEFAULT_NAME }
        var name = base
        var n = 2
        while (File(root, name).exists()) {
            name = "$base-$n"
            n += 1
        }
        val dir = File(root, name)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(createdKey(name), now)
            .putLong(openedKey(name), now)
            .putString(KEY_ACTIVE, name)
            .apply()
        return Project(name = name, dir = dir, createdMs = now, lastOpenedMs = now)
    }

    /** Remember which project is open (the server scopes sessions by directory). */
    fun select(name: String) {
        if (name.isEmpty()) return
        File(root, name).mkdirs()
        prefs.edit()
            .putLong(openedKey(name), System.currentTimeMillis())
            .putString(KEY_ACTIVE, name)
            .apply()
    }

    fun exists(name: String): Boolean = File(root, sanitize(name)).isDirectory

    /**
     * The name [create] would actually use for [rawName]: sanitized, and suffixed
     * so it never collides with an existing directory. Used by the SAF importer so
     * an imported folder can be named after what the user picked without clobbering
     * anything, and exposed as one method so "create" and "import" cannot drift.
     */
    fun uniqueName(rawName: String): String {
        val base = sanitize(rawName).ifEmpty { DEFAULT_NAME }
        var name = base
        var n = 2
        while (File(root, name).exists()) {
            name = "$base-$n"
            n += 1
        }
        return name
    }

    /**
     * Rename a project's directory. The new name goes through the same sanitisation
     * as creation; a collision gets a numeric suffix. Preference history moves with
     * it, and the active pointer follows only when it pointed at the renamed project.
     *
     * Sessions are deliberately NOT rewritten: OpenCode stores a session's directory
     * at creation time, and this app does not have an endpoint to move them (and must
     * not invent one). Future sessions use the new directory.
     *
     * @return the renamed project, or null when [name] does not exist / the rename failed.
     */
    fun rename(name: String, rawNewName: String): Project? {
        val srcDir = File(root, name)
        if (!srcDir.isDirectory) return null
        val target = sanitize(rawNewName).ifEmpty { return null }
        if (target == name) return projects().firstOrNull { it.name == name }
        var finalName = target
        var n = 2
        while (File(root, finalName).exists()) {
            finalName = "$target-$n"
            n += 1
        }
        val dstDir = File(root, finalName)
        if (!ProjectIo.renameDir(srcDir, dstDir)) return null

        val created = prefs.getLong(createdKey(name), dstDir.lastModified())
        val opened = prefs.getLong(openedKey(name), 0L)
        val now = System.currentTimeMillis()
        prefs.edit()
            .remove(createdKey(name))
            .remove(openedKey(name))
            .apply()
        prefs.edit()
            .putLong(createdKey(finalName), created)
            .putLong(openedKey(finalName), if (opened > 0L) opened else now)
            .apply()
        if (prefs.getString(KEY_ACTIVE, "") == name) {
            prefs.edit().putString(KEY_ACTIVE, finalName).apply()
        }
        return Project(name = finalName, dir = dstDir, createdMs = created, lastOpenedMs = if (opened > 0L) opened else now)
    }

    /** Delete a project directory and its preference history. Returns success. */
    fun delete(name: String): Boolean {
        val dir = File(root, name)
        if (!dir.isDirectory) return false
        ProjectIo.deleteTree(dir)
        prefs.edit().remove(createdKey(name)).remove(openedKey(name)).apply()
        if (prefs.getString(KEY_ACTIVE, "") == name) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        }
        return true
    }

    /**
     * Register a directory that already exists under [root] (an import copied it
     * into place) without re-suffixing its name. Mirrors [create]'s bookkeeping.
     */
    fun adopt(name: String): Project {
        val dir = File(root, name)
        if (!dir.isDirectory) dir.mkdirs()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(createdKey(name), now)
            .putLong(openedKey(name), now)
            .putString(KEY_ACTIVE, name)
            .apply()
        return Project(name = name, dir = dir, createdMs = now, lastOpenedMs = now)
    }

    /** The absolute path of the workspace root (for the repository's session cleanup). */
    fun rootPath(): String = root.absolutePath

    /**
     * Move projects left behind by an earlier install into the current root.
     *
     * This is the AUTOMATIC path, run once per start. It is deliberately the
     * conservative one, because it moves files the user never asked to move:
     *
     *   * it does nothing when the current root already holds a project — a user
     *     who deleted a project must not see it come back;
     *   * it takes the first legacy root that still has projects and stops there,
     *     rather than merging several old locations into one (a merge is a decision
     *     the user should make, and [ProjectMigration.moveAll] exposes it as an
     *     explicit action);
     *   * it never overwrites a name, and it removes a legacy directory only once
     *     that directory is empty, so a partial or failed move leaves the originals
     *     in place instead of losing them.
     *
     * Preference history is keyed by project name, so it follows the directories
     * without being rewritten: the user's active project is still the active one.
     *
     * @return the number of project directories moved.
     */
    fun ensureMigrated(): Int {
        val candidates = legacyRoots.filter { it.absolutePath != root.absolutePath }
        if (candidates.isEmpty()) return 0
        if (projects().isNotEmpty()) return 0
        var moved = 0
        for (legacy in candidates) {
            if (!legacy.isDirectory) continue
            val outcome = ProjectMigration.moveAll(legacy, root, allowTargetNonEmpty = false)
            moved += outcome.movedCount
            if (outcome.movedCount > 0) break
        }
        return moved
    }

    /** Where the projects live, as the app would show it (diagnostics + the file browser). */
    fun locationLabel(): String = root.absolutePath

    /**
     * Move every project from [from] into this store's root, for the user-facing
     * "make my projects visible" action after they grant All files access or pick
     * a folder. Unlike [ensureMigrated] this allows a non-empty destination (the
     * user asked for the move), and it reports what happened so the UI can say so.
     */
    fun moveFrom(from: File): ProjectMigration.Outcome =
        ProjectMigration.moveAll(from, root, allowTargetNonEmpty = true)

    private fun createdKey(name: String) = "created:$name"
    private fun openedKey(name: String) = "opened:$name"

    companion object {
        /** Phase 5's single workspace; adopted rather than migrated. */
        const val DEFAULT_NAME = "mobile"

        /**
         * Phase 10 continuation v4, item 4: the project the workspace step creates
         * ("project 1 -> <workspace>/1", the model the brief describes). A plain
         * number is not a placeholder - it is the folder name on disk, and the user
         * can rename it from the project list afterwards.
         */
        const val FIRST_PROJECT_NAME = "1"
        private const val PREFS = "projects"
        private const val KEY_ACTIVE = "active"

        /**
         * Filesystem-safe project name: letters, digits, dot, underscore, dash.
         * Everything else (including spaces and separators) becomes a dash, a
         * leading dot is dropped so the directory is never hidden, and the result
         * is length-capped. Pure, so it is covered by a JVM unit test.
         */
        fun sanitize(raw: String): String {
            val cleaned = raw.trim()
                .map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '-' }
                .joinToString("")
                .replace(Regex("-{2,}"), "-")
                .trim('-', '.')
            return cleaned.take(40).trimEnd('.', '-')
        }

        @Volatile private var instance: ProjectStore? = null

        /**
         * Drop the cached store so the next [get] re-resolves the root. Called when
         * the storage mode changes at runtime (All files access granted, or a folder
         * chosen), together with the migration that moves existing projects.
         */
        fun refresh() {
            synchronized(this) { instance = null }
        }

        fun get(context: Context): ProjectStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    val paths = ai.opencode.android.runtime.RuntimePaths.get(context)
                    ProjectStore(
                        root = paths.workspaces,
                        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                        // Both older locations, newest first. Roots equal to the
                        // current root are filtered inside ensureMigrated, so this is
                        // correct for every combination (public / app-external /
                        // internal) without a branch here.
                        legacyRoots = listOfNotNull(paths.externalWorkspaces, paths.internalWorkspaces),
                    )
                }.also { instance = it }
            }
    }
}
