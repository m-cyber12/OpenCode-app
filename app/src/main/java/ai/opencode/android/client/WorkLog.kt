package ai.opencode.android.client

import org.json.JSONObject

/**
 * v7: the read models behind the two new project surfaces.
 *
 *  * CHANGES - "what did the agent do to my files, stage by stage": every
 *    write/edit/patch tool call in the transcript, grouped by the assistant turn
 *    that made it, newest turn first, with the per-file +/- counts and the patch
 *    the server already computed ([ToolMeta]).
 *  * TERMINAL - "what did the agent run": every shell tool call as a console
 *    entry (command, captured output, exit), oldest first, because a terminal
 *    scrolls down.
 *
 * Both are PURE projections of [Transcript.SessionView] - no new state is kept
 * anywhere (the Phase 6 fence: the UI stays a thin client of the server, and a
 * pure function of server state is JVM-testable). Parsing uses org.json only,
 * exactly like [ToolMetaParser], and never throws on garbage input.
 */
object WorkLog {

    /** One file the agent touched in one tool call. */
    data class Change(
        val partId: String,
        val file: String,
        val tool: String,
        val status: String,
        val additions: Int,
        val deletions: Int,
        val patch: String,
        val atMs: Long,
    )

    /** All file changes made by one assistant turn, newest turn first. */
    data class TurnChanges(
        val messageID: String,
        val turnIndex: Int,
        val atMs: Long,
        val changes: List<Change>,
    ) {
        val additions: Int get() = changes.sumOf { it.additions }
        val deletions: Int get() = changes.sumOf { it.deletions }
    }

    /** One shell command the agent ran, as a console entry. */
    data class Command(
        val partId: String,
        val command: String,
        val description: String,
        val output: String,
        val status: String,
        val exit: Int?,
        val atMs: Long,
        val durationMs: Long,
    )

    /**
     * File changes grouped by assistant turn, NEWEST first - the page answers
     * "what just happened", so the latest stage leads. A turn with no file
     * changes is omitted entirely rather than shown empty.
     */
    fun changes(view: Transcript.SessionView?): List<TurnChanges> {
        if (view == null) return emptyList()
        val out = ArrayList<TurnChanges>()
        var turnIndex = 0
        for (message in view.messages) {
            if (message.role != "assistant") continue
            turnIndex++
            val turn = ArrayList<Change>()
            for (part in message.parts) {
                if (part.type != "tool") continue
                val kind = ToolKinds.of(part.tool)
                val meta = ToolMetaParser.parse(part.metadata)
                val writes = kind == ToolKind.FILE_WRITE || kind == ToolKind.FILE_EDIT
                if (!writes && !meta.hasDiff) continue
                val file = meta.diffFile.ifBlank { inputField(part.input, "filePath", "path") }
                turn.add(
                    Change(
                        partId = part.id,
                        file = file.ifBlank { part.title },
                        tool = part.tool,
                        status = part.status,
                        additions = meta.additions,
                        deletions = meta.deletions,
                        patch = meta.diff,
                        atMs = if (part.timeStart > 0) part.timeStart else message.createdMs,
                    ),
                )
            }
            if (turn.isNotEmpty()) {
                out.add(
                    TurnChanges(
                        messageID = message.id,
                        turnIndex = turnIndex,
                        atMs = message.createdMs,
                        changes = turn,
                    ),
                )
            }
        }
        out.reverse()
        return out
    }

    /**
     * Shell commands in transcript order, OLDEST first - a console reads
     * top-down. Includes running and failed commands: a terminal that hid
     * failures would be lying about what the agent did.
     */
    fun commands(view: Transcript.SessionView?): List<Command> {
        if (view == null) return emptyList()
        val out = ArrayList<Command>()
        for (message in view.messages) {
            if (message.role != "assistant") continue
            for (part in message.parts) {
                if (part.type != "tool") continue
                if (ToolKinds.of(part.tool) != ToolKind.SHELL) continue
                val meta = ToolMetaParser.parse(part.metadata)
                val command = inputField(part.input, "command")
                out.add(
                    Command(
                        partId = part.id,
                        command = command.ifBlank { part.title },
                        description = inputField(part.input, "description"),
                        output = meta.output.ifBlank { part.output },
                        status = part.status,
                        exit = meta.exit,
                        atMs = if (part.timeStart > 0) part.timeStart else message.createdMs,
                        durationMs = if (part.timeEnd > part.timeStart && part.timeStart > 0) {
                            part.timeEnd - part.timeStart
                        } else {
                            0L
                        },
                    ),
                )
            }
        }
        return out
    }

    /** First non-blank of the named string fields in a tool-input JSON blob. */
    private fun inputField(input: String, vararg keys: String): String {
        if (input.isBlank()) return ""
        val o = runCatching { JSONObject(input) }.getOrNull() ?: return ""
        for (key in keys) {
            val v = o.optString(key)
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
