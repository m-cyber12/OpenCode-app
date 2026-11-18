package ai.opencode.android.runtime

import ai.opencode.android.client.v2.JsoncEdit
import java.io.File

/**
 * Defaults the app writes into the global `opencode.json(c)` before the server starts.
 *
 * v2.2.5 ships the agent's `todowrite` tool OFF: it is registered only when
 * `experimental.todo` is true. The app's todo bar and the agent's own checklist both
 * depend on that tool, so after the v2 move the model had no todo tool at all and the
 * bar stayed hidden. The key is written only when the user has not set `todo` either way,
 * so an explicit choice in the file always wins.
 *
 * Comments and other keys survive ([JsoncEdit.withKey]); the write is atomic.
 */
internal object GlobalConfigDefaults {

    /** Returns true when the file was changed. Throws on a broken file: nothing is written then. */
    fun ensureTodoTool(configDir: File): Boolean {
        val file = configFile(configDir)
        configDir.mkdirs()
        val text = if (file.isFile) file.readText() else "{}\n"
        val root = JsoncEdit.parseObject(text)
        val experimental = root.optJSONObject("experimental")
        if (experimental != null && experimental.has("todo")) return false
        val next = JsoncEdit.withKey(
            text,
            "experimental",
            org.json.JSONObject(experimental?.toString() ?: "{}").put("todo", true).toString(2),
        )
        // Parsing is the check: a broken edit throws before the real file is touched.
        JsoncEdit.parseObject(next)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(if (next.endsWith("\n")) next else "$next\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        return true
    }

    /** The existing `opencode.json` or `opencode.jsonc`, else a new `.jsonc` (the same rule the config writes use). */
    private fun configFile(dir: File): File {
        val existing = listOf("opencode.json", "opencode.jsonc").map { File(dir, it) }
        return existing.firstOrNull { it.isFile } ?: File(dir, "opencode.jsonc")
    }
}
