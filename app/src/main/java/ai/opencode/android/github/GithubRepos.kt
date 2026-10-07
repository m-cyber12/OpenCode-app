package ai.opencode.android.github

import org.json.JSONArray

/**
 * v9.25 (owner): the Arena-style repo UX. When GitHub is connected, the
 * projects surface lists the user's repositories; picking one makes the APP
 * clone it (before any agent runs - exactly how the Arena platform prepares
 * its own agents' checkouts) and puts the chat on its own
 * `opencode/chat-<id>` branch.
 *
 * This object is the pure, JVM-tested part: URLs and JSON parsing. Base URLs
 * arrive as parameters (they live in strings.xml - this repo bans URL
 * literals in Kotlin, and the tests get to inject fakes).
 */
object GithubRepos {

    data class Repo(
        val name: String,
        val fullName: String,
        val defaultBranch: String,
        val private: Boolean,
    )

    /**
     * The viewer's repositories, most recently pushed first - the repo you
     * want is almost always the one you just worked on. 100 is the API's
     * page maximum; one page is deliberate (a phone picker, not a browser).
     */
    fun listUrl(apiBase: String): String =
        apiBase.trimEnd('/') + "/user/repos?per_page=100&sort=pushed"

    /** Headers for every GitHub API call. The token NEVER goes in a URL. */
    fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    fun parseRepos(json: String): List<Repo> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Repo(
                name = o.getString("name"),
                fullName = o.getString("full_name"),
                defaultBranch = o.optString("default_branch", "main"),
                private = o.optBoolean("private", false),
            )
        }
    }

    /** `https://github.com/<owner>/<repo>.git` - the token rides in a header. */
    fun cloneUrl(webBase: String, fullName: String): String =
        webBase.trimEnd('/') + "/" + fullName + ".git"
}
