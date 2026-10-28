package ai.opencode.android.client

/**
 * v11 (owner, B.108): no trace of the owner's GitHub account anywhere in the app.
 *
 * The account name reached the screen in three ways: the `owner/repo` text of the
 * repository picker, the tool cards (paths and commands that carry the owner's
 * repository URL), and the application id `io.github.<account>.opencode`, which
 * sits inside app-data paths the tool cards show.
 *
 * Two rules, applied where text ENTERS the app's transcript and diagnostics:
 *  1. the application-id pattern `io.github.<x>.opencode[.suffix]` shows as `<app>`;
 *  2. every account name the app has learned (from its GitHub project records) shows
 *     as `<owner>`, case-insensitively, as a whole word.
 *
 * Only the names the app itself has seen are scrubbed - nothing is hard-coded here,
 * so the account name never appears in the APK. This is a display rule, not a
 * security boundary: the agent still sees the real text on its side.
 */
object AccountRedaction {

    private val APP_ID = Regex("""io\.github\.[A-Za-z0-9_-]+\.opencode""")
    private val LOGIN = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$""")

    @Volatile
    private var logins: Set<String> = emptySet()

    private val lock = Any()

    /** Learn an account name (a GitHub login). Anything that is not a login is ignored. */
    fun remember(login: String) {
        val l = login.trim()
        if (!LOGIN.matches(l)) return
        synchronized(lock) {
            if (l.lowercase() !in logins) logins = logins + l.lowercase()
        }
    }

    /** Learn the account from an `owner/repo` string. */
    fun rememberRepo(fullName: String) {
        val owner = fullName.substringBefore('/', missingDelimiterValue = "")
        if (owner.isNotEmpty()) remember(owner)
    }

    /** The text with the app id and every learned account name replaced. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = APP_ID.replace(text, "<app>")
        for (login in logins) {
            if (!out.contains(login, ignoreCase = true)) continue
            val word = Regex("(?<![A-Za-z0-9-])" + Regex.escape(login) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)
            out = word.replace(out, "<owner>")
        }
        return out
    }
}
