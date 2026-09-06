package ai.opencode.android.projects

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * The list of on-device project directories the agent can work in.
 *
 * A "project" is nothing more than a directory under the app's own
 * `files/workspaces` root that is used as the OpenCode instance directory
 * (`OpenCodeApi.directory`, which upstream resolves per request). The server
 * owns everything else about a session; this store only remembers which
 * directories exist and which one the user last opened, because the choice has
 * to survive a process restart and the user must never be asked for a path.
 *
 * Phase 6 keeps this deliberately small - list, create, open. Renaming,
 * deleting, importing an existing tree, git init and per-project credentials are
 * workspace management, which the phase plan puts in Phase 7.
 */
data class Project(
    val name: String,
    val dir: File,
    val createdMs: Long,
    val lastOpenedMs: Long,
) {
    val path: String get() = dir.absolutePath
}

class ProjectStore private constructor(
    private val root: File,
    private val prefs: SharedPreferences,
) {

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

    private fun createdKey(name: String) = "created:$name"
    private fun openedKey(name: String) = "opened:$name"

    companion object {
        /** Phase 5's single workspace; adopted rather than migrated. */
        const val DEFAULT_NAME = "mobile"
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

        fun get(context: Context): ProjectStore =
            instance ?: synchronized(this) {
                instance ?: ProjectStore(
                    root = File(context.filesDir, "workspaces"),
                    prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
