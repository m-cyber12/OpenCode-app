package ai.opencode.android.security

/**
 * v9.23 (owner): the first connector - GitHub over a personal access token.
 *
 * Contract, exactly as the owner specified it:
 *  - the app gives the user a link that creates the token with the required
 *    scope already selected (GitHub's CLASSIC token page accepts `scopes` and
 *    `description` query parameters; the fine-grained page prefills nothing,
 *    which is why the classic flow is linked);
 *  - the token is pasted into the app, stored in the Android Keystore
 *    ([SecretStore]), and handed to the model ONLY as the environment value
 *    `key` of the local server process - it never travels through chat,
 *    config files, or any remote service;
 *  - the UI text still names the needed scope in words, for the case where
 *    GitHub changes or ignores the prefill.
 */
object GithubConnector {

    /** Keystore entry name (validated by [SecretNames]). */
    const val SECRET_NAME = "connector:github"

    /** The environment variable the agent reads - the owner's chosen name. */
    const val ENV_VAR = "key"

    // The classic-token creation URL (with `scopes=repo` pre-selected) lives
    // in strings.xml as `github_token_url` - this repo keeps every URL in
    // resources (enforced by check-ui-strings). GithubConnectorTest pins the
    // scope prefill by reading that resource file.

    /**
     * Loose shape check so an obvious paste accident (fragment, empty line,
     * token with spaces) is rejected in the UI instead of silently breaking
     * the agent's git pushes later. Deliberately NOT a prefix whitelist:
     * GitHub has changed token prefixes before (ghp_, github_pat_, gho_...)
     * and a connector must not go stale with them.
     */
    fun looksLikeToken(raw: String): Boolean {
        val t = raw.trim()
        return t.length >= 20 && t.none { it.isWhitespace() }
    }

    fun normalize(raw: String): String = raw.trim()
}
