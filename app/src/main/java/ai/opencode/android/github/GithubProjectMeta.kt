package ai.opencode.android.github

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * v9.41 (owner item 3): the intent marker of a GitHub project.
 *
 * "Create a project from a repo" must be INSTANT - the clone happens at the
 * first prompt, right before the model touches the code, exactly where the
 * wait belongs. In the window between creation and first prompt there is no
 * `.git` to read an origin from, so the project carries its own fact instead.
 *
 * The marker deliberately lives in the app's PRIVATE storage (filesDir), not
 * inside the project folder: a `.opencode/` or a marker file inside the
 * worktree would ride the model's `git add -A` into the user's repository -
 * the exact "the sandbox shows me machinery" impression item 4 is about.
 * Keyed by project name, moved on rename, deleted with the project.
 */
object GithubProjectMeta {

    /** The repository the project was created around: "owner/name". */
    data class Meta(val repo: String)

    private fun fileFor(context: Context, project: String): File? {
        if (project.isEmpty()) return null
        val safe = project.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        if (safe.isEmpty()) return null
        return File(File(context.filesDir, "github-meta"), "$safe.json")
    }

    fun write(context: Context, project: String, repo: String) {
        ai.opencode.android.client.AccountRedaction.rememberRepo(repo)
        runCatching {
            val f = fileFor(context, project) ?: return
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("repo", repo).toString())
        }
    }

    fun read(context: Context, project: String): Meta? = runCatching {
        val f = fileFor(context, project) ?: return null
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val repo = o.optString("repo")
        // Learned here too, so an app restart scrubs the owner's name from the start.
        ai.opencode.android.client.AccountRedaction.rememberRepo(repo)
        if (repo.isEmpty()) null else Meta(repo)
    }.getOrNull()

    fun has(context: Context, project: String): Boolean = read(context, project) != null

    fun rename(context: Context, from: String, to: String) {
        runCatching {
            val src = fileFor(context, from) ?: return
            if (!src.isFile) return
            val dst = fileFor(context, to) ?: return
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    fun delete(context: Context, project: String) {
        runCatching { fileFor(context, project)?.delete() }
    }
}
