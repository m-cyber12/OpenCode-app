package ai.opencode.android.client

import org.json.JSONObject

/**
 * What a tool call actually did, read out of the fields OpenCode itself puts on a
 * tool part - nothing is inferred, and nothing is re-derived client-side.
 *
 * Shapes come from the pinned upstream source:
 *  * shell/bash (`packages/opencode/src/tool/shell.ts`): `title` = the command,
 *    `state.metadata` = `{output, exit, truncated, outputPath?}`, `state.output`
 *    = the full output.
 *  * write/edit (`tool/write.ts`, `tool/edit.ts`): `title` = the path relative to
 *    the worktree, `state.metadata` = `{diff, filediff:{file,patch,additions,
 *    deletions}, diagnostics}`.
 *  * read/glob/grep/webfetch: `title` + `state.output`, no diff.
 *
 * Parsing is pure (org.json only) so it is covered by JVM unit tests with the real
 * field names rather than being discovered on a device.
 */
data class ToolMeta(
    val output: String = "",
    val exit: Int? = null,
    val truncated: Boolean = false,
    val outputPath: String = "",
    val diff: String = "",
    val diffFile: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val diagnosticsCount: Int = 0,
) {
    val hasDiff: Boolean get() = diff.isNotBlank() || additions > 0 || deletions > 0
}

object ToolMetaParser {

    /** Parse `state.metadata` (already a JSON string in [Transcript.Part]). */
    fun parse(raw: String): ToolMeta {
        if (raw.isBlank() || raw == "null") return ToolMeta()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return ToolMeta()
        val filediff = o.optJSONObject("filediff")
        val diagnostics = o.optJSONObject("diagnostics")
        var diagCount = 0
        if (diagnostics != null) {
            for (k in diagnostics.keys()) {
                diagCount += diagnostics.optJSONArray(k)?.length() ?: 0
            }
        }
        return ToolMeta(
            output = o.optString("output"),
            exit = if (o.has("exit") && !o.isNull("exit")) o.optInt("exit") else null,
            truncated = o.optBoolean("truncated"),
            outputPath = o.optString("outputPath"),
            diff = o.optString("diff").ifBlank { filediff?.optString("patch") ?: "" },
            diffFile = filediff?.optString("file") ?: o.optString("filePath"),
            additions = filediff?.optInt("additions") ?: 0,
            deletions = filediff?.optInt("deletions") ?: 0,
            diagnosticsCount = diagCount,
        )
    }
}

/**
 * The family a tool belongs to, for choosing an icon and a one-line human summary.
 * The upstream tool NAME is always shown verbatim beside it: this categorisation is
 * presentation, and it must never be the only thing the user sees.
 */
enum class ToolKind { SHELL, FILE_READ, FILE_WRITE, FILE_EDIT, SEARCH, WEB, TASK, TODO, PLAN, MCP, OTHER }

object ToolKinds {

    private val BUILTINS = mapOf(
        "bash" to ToolKind.SHELL,
        "shell" to ToolKind.SHELL,
        "read" to ToolKind.FILE_READ,
        "write" to ToolKind.FILE_WRITE,
        "edit" to ToolKind.FILE_EDIT,
        "multiedit" to ToolKind.FILE_EDIT,
        "apply_patch" to ToolKind.FILE_EDIT,
        "glob" to ToolKind.SEARCH,
        "grep" to ToolKind.SEARCH,
        "list" to ToolKind.SEARCH,
        "ls" to ToolKind.SEARCH,
        "webfetch" to ToolKind.WEB,
        "websearch" to ToolKind.WEB,
        "task" to ToolKind.TASK,
        "subtask" to ToolKind.TASK,
        "todo" to ToolKind.TODO,
        "todowrite" to ToolKind.TODO,
        "todoread" to ToolKind.TODO,
        "plan" to ToolKind.PLAN,
        "plan_enter" to ToolKind.PLAN,
        "plan_exit" to ToolKind.PLAN,
        "lsp" to ToolKind.OTHER,
        "skill" to ToolKind.OTHER,
        "code_mode" to ToolKind.OTHER,
        "question" to ToolKind.OTHER,
        "invalid" to ToolKind.OTHER,
    )

    /**
     * Upstream registers MCP tools as `<serverName>_<toolName>` (see the Phase 3
     * gate evidence: `gates-mcp_echo`), so a name that is not a builtin and carries
     * an underscore is an MCP tool. That is a naming convention, not a guarantee -
     * which is why the raw name is always displayed next to the category.
     */
    fun of(tool: String): ToolKind {
        val key = tool.trim().lowercase()
        if (key.isEmpty()) return ToolKind.OTHER
        BUILTINS[key]?.let { return it }
        if (key.contains('_')) return ToolKind.MCP
        return ToolKind.OTHER
    }
}
