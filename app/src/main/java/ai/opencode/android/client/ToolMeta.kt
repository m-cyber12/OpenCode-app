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
 *  * apply_patch (`tool/apply_patch.ts`): NO `filediff`. Its metadata is one
 *    aggregate `diff` for the whole patch plus `files: [{filePath, relativePath,
 *    type, patch, additions, deletions, movePath}]`, one entry per file. Reading
 *    only `filediff` showed those edits as a blank row with "+0 -0" (owner, B.108).
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
    /** One entry per file for multi-file tools (apply_patch); empty otherwise. */
    val files: List<ToolFile> = emptyList(),
) {
    val hasDiff: Boolean get() = diff.isNotBlank() || additions > 0 || deletions > 0 || files.isNotEmpty()
}

/** One file of a multi-file edit (apply_patch's `files` entry). */
data class ToolFile(
    val path: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val movePath: String = "",
)

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
        val files = parseFiles(o.optJSONArray("files"))
        val diff = o.optString("diff").ifBlank { filediff?.optString("patch") ?: "" }
        // Counts: the server's own numbers when it sent them (edit/write `filediff`);
        // otherwise the sum over apply_patch's files; otherwise counted from the patch
        // itself, so a tool that sends a diff but no counts still shows real +/-.
        val additions: Int
        val deletions: Int
        when {
            filediff != null -> {
                additions = filediff.optInt("additions")
                deletions = filediff.optInt("deletions")
            }
            files.isNotEmpty() -> {
                additions = files.sumOf { it.additions }
                deletions = files.sumOf { it.deletions }
            }
            else -> {
                val counted = countPatch(diff)
                additions = counted.first
                deletions = counted.second
            }
        }
        return ToolMeta(
            output = o.optString("output"),
            exit = if (o.has("exit") && !o.isNull("exit")) o.optInt("exit") else null,
            truncated = o.optBoolean("truncated"),
            outputPath = o.optString("outputPath"),
            diff = diff,
            diffFile = filediff?.optString("file") ?: o.optString("filePath"),
            additions = additions,
            deletions = deletions,
            diagnosticsCount = diagCount,
            files = files,
        )
    }

    private fun parseFiles(arr: org.json.JSONArray?): List<ToolFile> {
        if (arr == null) return emptyList()
        val out = ArrayList<ToolFile>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val path = f.optString("relativePath").ifBlank { f.optString("filePath") }
            val patch = f.optString("patch")
            val add = f.optInt("additions", -1).takeIf { it >= 0 } ?: countPatch(patch).first
            val del = f.optInt("deletions", -1).takeIf { it >= 0 } ?: countPatch(patch).second
            out.add(ToolFile(path = path, patch = patch, additions = add, deletions = del, movePath = f.optString("movePath")))
        }
        return out
    }

    /**
     * Added and removed line counts of a unified patch. A file header is a `---` line
     * immediately followed by its `+++` line (whatever the names are); those two lines
     * are not changes. Any other `-` line is a removal even if its text begins with
     * `--` (an SQL comment removed from a file reads as `--- comment`).
     */
    fun countPatch(patch: String): Pair<Int, Int> {
        val lines = patch.lines()
        var add = 0
        var del = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ")) {
                i += 2
                continue
            }
            when {
                line.startsWith("+") -> add++
                line.startsWith("-") -> del++
            }
            i++
        }
        return add to del
    }
}

/** One entry of the agent's own plan, as `todowrite` reported it. */
data class TodoItem(val content: String, val status: String)

/**
 * The agent's plan checklist, read out of the fields OpenCode itself puts on a
 * `todowrite`/`todoread` tool part - nothing inferred, nothing re-derived.
 *
 * Shape from the pinned upstream source (`packages/opencode/src/tool/todo.ts`):
 * the call INPUT is `{todos: [{id, content, status, priority?}]}` with `status`
 * one of `pending | in_progress | completed | cancelled`; `state.metadata.todos`
 * echoes the same array back. Metadata wins when both parse, because it is the
 * server's own record of what it stored. Pure org.json so a JVM unit test covers
 * the real field names, and any malformed document parses to an empty list - the
 * card then falls back to its generic face rather than inventing rows.
 */
object TodoParser {

    fun parse(input: String, metadata: String = ""): List<TodoItem> {
        val fromMetadata = fromJson(metadata)
        if (fromMetadata.isNotEmpty()) return fromMetadata
        return fromJson(input)
    }

    private fun fromJson(raw: String): List<TodoItem> {
        if (raw.isBlank() || raw == "null") return emptyList()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val todos = o.optJSONArray("todos") ?: return emptyList()
        val out = ArrayList<TodoItem>(todos.length())
        for (i in 0 until todos.length()) {
            val item = todos.optJSONObject(i) ?: continue
            val content = item.optString("content").trim()
            if (content.isEmpty()) continue
            out.add(TodoItem(content = content, status = item.optString("status").trim().lowercase()))
        }
        return out
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
