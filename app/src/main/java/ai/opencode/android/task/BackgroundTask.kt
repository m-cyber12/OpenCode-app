package ai.opencode.android.task

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * Background engineering (agent.md section 12), first slice.
 *
 * A task is a spec file `.tasks/<name>.json`: a prompt, a time limit, and one
 * completion condition, a project file that must exist when the task ends. The
 * app runs the prompt in its own session, watches it until the turn ends or the
 * time limit passes (then it aborts the turn), and writes `.tasks/<name>.result.txt`.
 *
 * Limits: time only. Upstream does not expose a token or cost budget, so there is
 * none here. Tools run through the same permission gate as any turn. A task that
 * waits for an answer nobody sees ends at its time limit; nothing is auto-approved.
 * One task runs at a time, while the app is alive.
 */
object BackgroundTask {
    const val DIR = ".tasks"
    const val MAX_PROMPT = 4000
    const val MAX_MINUTES = 60
    const val DEFAULT_MINUTES = 20

    /** Upstream can show a new session as idle for a moment before the turn starts. */
    const val START_GRACE_MS = 10_000L

    /** Consecutive failed status reads before the task is failed. */
    const val MAX_POLL_ERRORS = 3
    private val NAME = Regex("[A-Za-z0-9_-]{1,40}")

    data class Spec(val name: String, val prompt: String, val minutes: Int, val doneFile: String)

    sealed class Parsed {
        data class Ok(val spec: Spec) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    enum class Outcome { DONE, INCOMPLETE, TIMEOUT, FAILED }

    /** What one run produced. [error] is set only for FAILED. */
    data class Finished(
        val outcome: Outcome,
        val sessionID: String,
        val ms: Long,
        val doneExists: Boolean,
        val lastReply: String,
        val error: String?,
    )

    /** The four session calls the runner needs. The app wraps its API client in this. */
    interface Port {
        fun createSession(title: String): String
        fun prompt(sessionID: String, text: String)

        /** True while the session's turn is running. */
        fun busy(sessionID: String): Boolean
        fun abort(sessionID: String)
        fun lastReply(sessionID: String): String
    }

    /** `<name>.json` spec, named by its file name. Anything unclear is rejected with a reason. */
    fun parse(name: String, text: String): Parsed {
        if (!NAME.matches(name)) return Parsed.Invalid("file name must be 1-40 letters, digits, '-' or '_'")
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Parsed.Invalid("task file is not valid JSON")
        }
        val prompt = root.optString("prompt", "").trim()
        if (prompt.isEmpty() || prompt.length > MAX_PROMPT) return Parsed.Invalid("prompt must be 1-$MAX_PROMPT characters")
        val minutes = if (root.has("minutes")) root.optInt("minutes", -1) else DEFAULT_MINUTES
        if (minutes !in 1..MAX_MINUTES) return Parsed.Invalid("minutes must be 1-$MAX_MINUTES")
        val done = root.optJSONObject("done_when")
            ?: return Parsed.Invalid("done_when must name a file, e.g. {\"file\": \"reports/debt.md\"}")
        val file = done.optString("file", "").trim()
        if (!safeRelative(file)) {
            return Parsed.Invalid("done_when.file must be a relative path inside the project, without \"..\"")
        }
        return Parsed.Ok(Spec(name, prompt, minutes, file))
    }

    /** A project-relative path: no absolute start, no "..", no drive or backslash tricks. */
    fun safeRelative(path: String): Boolean =
        path.isNotEmpty() &&
            path.length <= 200 &&
            !path.startsWith("/") &&
            !path.contains('\\') &&
            !path.contains(':') &&
            path.split('/').none { it == ".." || it.isEmpty() }

    /**
     * The outcome. The completion file decides DONE, even after a timeout: the
     * work was saved. An error beats everything else.
     */
    fun decide(doneExists: Boolean, timedOut: Boolean, error: String?): Outcome = when {
        error != null -> Outcome.FAILED
        doneExists -> Outcome.DONE
        timedOut -> Outcome.TIMEOUT
        else -> Outcome.INCOMPLETE
    }

    /**
     * Run the spec to an outcome. Blocking calls go through [port], so call this
     * off the main thread. [doneExists] checks the completion file; it is read
     * only after the session is idle, so a file written mid-turn is judged
     * with the whole turn.
     */
    suspend fun run(
        spec: Spec,
        port: Port,
        doneExists: () -> Boolean,
        limitMs: Long = spec.minutes * 60_000L,
        pollMs: Long = 5_000L,
        graceMs: Long = START_GRACE_MS,
    ): Finished {
        val started = System.currentTimeMillis()
        fun finish(sid: String, timedOut: Boolean, error: String?): Finished {
            val reply = runCatching { port.lastReply(sid) }.getOrDefault("")
            val exists = runCatching { doneExists() }.getOrDefault(false)
            return Finished(
                outcome = decide(exists, timedOut, error),
                sessionID = sid,
                ms = System.currentTimeMillis() - started,
                doneExists = exists,
                lastReply = reply,
                error = error,
            )
        }

        val sid = try {
            port.createSession("Background: ${spec.name}")
        } catch (e: Exception) {
            return Finished(Outcome.FAILED, "", 0, false, "", "could not create a session: ${e.message}")
        }
        try {
            port.prompt(sid, spec.prompt)
        } catch (e: Exception) {
            return finish(sid, timedOut = false, error = "could not send the prompt: ${e.message}")
        }

        var sawBusy = false
        var pollErrors = 0
        var timedOut = false
        while (true) {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= limitMs) {
                timedOut = true
                runCatching { port.abort(sid) }
                break
            }
            val busy: Boolean? = try {
                port.busy(sid).also { pollErrors = 0 }
            } catch (e: Exception) {
                pollErrors++
                if (pollErrors >= MAX_POLL_ERRORS) {
                    runCatching { port.abort(sid) }
                    return finish(sid, timedOut = false, error = "status unreadable: ${e.message}")
                }
                null
            }
            if (busy != null) {
                if (busy) {
                    sawBusy = true
                } else if (sawBusy || elapsed >= graceMs) {
                    break
                }
            }
            delay(pollMs)
        }
        return finish(sid, timedOut, error = null)
    }

    /** The text written to `.tasks/<name>.result.txt`. The first line is the verdict. */
    fun report(spec: Spec, f: Finished): String {
        val sb = StringBuilder()
        sb.append("TASK ${spec.name} ${f.outcome.name} ms=${f.ms} session=${f.sessionID}\n")
        if (f.error != null) sb.append("  error: ${f.error}\n")
        sb.append("  done_when: ${spec.doneFile} ${if (f.doneExists) "exists" else "missing"}\n")
        val reply = f.lastReply.trim()
        if (reply.isNotEmpty()) {
            sb.append("  last reply: ${reply.take(1500).replace('\n', ' ')}\n")
        }
        return sb.toString()
    }

    fun invalidReport(name: String, reason: String): String = "TASK $name INVALID: $reason\n"

    /** The first spec with no result file yet, by name. */
    fun pending(projectDir: File): File? =
        File(projectDir, DIR).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.firstOrNull { !resultFile(projectDir, it.name.removeSuffix(".json")).exists() }

    fun resultFile(projectDir: File, name: String): File = File(File(projectDir, DIR), "$name.result.txt")

    /** The completion file counts only if it is non-empty: an empty file is not a finished report. */
    fun doneFileReady(projectDir: File, spec: Spec): Boolean {
        val f = File(projectDir, spec.doneFile)
        return f.isFile && f.length() > 0
    }
}
