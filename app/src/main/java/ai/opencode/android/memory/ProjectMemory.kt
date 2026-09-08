package ai.opencode.android.memory

import java.io.File

/**
 * OpenCode's own persistent memory mechanism, surfaced to the user — not a new one.
 *
 * OpenCode already loads rule files into the agent's context on every session
 * (upstream `packages/opencode/src/session/instruction.ts`): a project-scoped
 * `AGENTS.md` at the project root, and a global one at
 * `$XDG_CONFIG_HOME/opencode/AGENTS.md`. This class only reads and writes those
 * exact files, so the app reuses OpenCode's mechanism instead of inventing a
 * second, silently-injected memory store.
 *
 * Because both files live in app-private storage as plain markdown, they satisfy
 * every requirement the phase puts on a memory layer:
 *
 *  * inspectable  — shown in Settings, and a normal file the agent can read;
 *  * editable     — the Settings editor rewrites them in place;
 *  * scoped       — one file per project plus one global file, exactly the two
 *                   scopes OpenCode itself distinguishes;
 *  * removable    — a blank save or the Remove action deletes the file;
 *  * privacy-conscious — nothing is sent anywhere; OpenCode only ever reads these
 *                   into the model's context, on this device, like any rule file.
 */
class ProjectMemory(
    private val workspacesRoot: File,
    private val globalRulesDir: File,
) {

    fun projectFile(projectName: String): File = File(workspacesRoot, "$projectName/$RULES_FILE")
    fun globalFile(): File = File(globalRulesDir, RULES_FILE)

    fun readProject(projectName: String): String = read(projectFile(projectName))
    fun readGlobal(): String = read(globalFile())

    fun hasProject(projectName: String): Boolean = projectFile(projectName).isFile
    fun hasGlobal(): Boolean = globalFile().isFile

    /** Write project rules. A blank value removes the file (empty = no rules). */
    fun writeProject(projectName: String, text: String): Boolean {
        if (projectName.isBlank()) return false
        return write(projectFile(projectName), text)
    }

    fun writeGlobal(text: String): Boolean = write(globalFile(), text)

    fun removeProject(projectName: String): Boolean =
        if (projectName.isBlank()) false else remove(projectFile(projectName))

    fun removeGlobal(): Boolean = remove(globalFile())

    private fun read(file: File): String = if (file.isFile) file.readText().trim() else ""

    private fun write(file: File, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) {
            remove(file)
            return true
        }
        file.parentFile?.mkdirs()
        file.writeText(t + "\n")
        return true
    }

    private fun remove(file: File): Boolean = if (file.exists()) file.delete() else true

    companion object {
        /** OpenCode's rule filename (project root and global config dir). */
        const val RULES_FILE = "AGENTS.md"
    }
}

/**
 * The memory editor's inputs, handed to the Settings screen as plain values so
 * the screen stays a pure function of its parameters.
 */
data class MemoryState(
    val projectName: String = "",
    val projectContent: String = "",
    val projectHasRules: Boolean = false,
    val globalContent: String = "",
    val globalHasRules: Boolean = false,
)
