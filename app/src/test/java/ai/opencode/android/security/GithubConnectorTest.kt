package ai.opencode.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.23: the GitHub connector contract (owner-specified). The pieces pinned
 * here are the ones that would fail SILENTLY in production if they drifted:
 * a secret name the Keystore layer rejects, a token page without the scope
 * prefill, or a shape check that starts refusing real GitHub tokens.
 */
class GithubConnectorTest {

    @Test
    fun secretNamePassesTheKeystoreNamePolicy() {
        // If this regresses, saving the token throws at runtime in Settings.
        assertTrue(SecretNames.isValid(GithubConnector.SECRET_NAME))
    }

    @Test
    fun tokenPageCarriesScopePrefillAndDescription() {
        // The URL lives in strings.xml (repo convention: no URL literals in
        // Kotlin, enforced by check-ui-strings), so pin the shipped resource.
        val strings = sequenceOf(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
        assertTrue("strings.xml not found from test working dir", strings != null)
        val xml = strings!!.readText()
        val url = Regex("""<string name="github_token_url"[^>]*>(.*?)</string>""")
            .find(xml)?.groupValues?.get(1)?.replace("&amp;", "&")
        assertTrue("github_token_url missing from strings.xml", url != null)
        assertTrue(url!!.startsWith("https://github.com/settings/tokens/new"))
        // The owner's requirement: the needed scopes arrive pre-selected.
        // v9.24: repo AND workflow - "access like Arena's" (contents, PRs,
        // Actions, and pushing .github/workflows files; repo alone gets 403
        // on workflow pushes - the exact failure this session's bot token hits).
        assertTrue(url.contains("scopes=repo,workflow"))
        assertTrue(url.contains("description="))
    }

    @Test
    fun envVarIsTheOwnersChosenName() {
        assertEquals("key", GithubConnector.ENV_VAR)
    }

    @Test
    fun shapeCheckAcceptsRealTokensAndRejectsPasteAccidents() {
        // Current and historical GitHub token shapes - all must pass, because
        // the check is deliberately NOT a prefix whitelist.
        assertTrue(GithubConnector.looksLikeToken("ghp_16C7e42F292c6912E7710c838347Ae178B4a"))
        assertTrue(GithubConnector.looksLikeToken("github_pat_11ABCDEFG0_abcdefghijklmnopqrstuvwxyz"))
        assertTrue(GithubConnector.looksLikeToken("  ghp_16C7e42F292c6912E7710c838347Ae178B4a  ")) // trimmed first
        assertTrue(GithubConnector.looksLikeToken("40charhexoldstyletoken40charhexoldstyle1"))
        // Paste accidents.
        assertFalse(GithubConnector.looksLikeToken(""))
        assertFalse(GithubConnector.looksLikeToken("   "))
        assertFalse(GithubConnector.looksLikeToken("ghp_short"))
        assertFalse(GithubConnector.looksLikeToken("ghp_has a space inside somewhere 123456"))
        assertFalse(GithubConnector.looksLikeToken("line1\nline2line2line2line2line2"))
    }

    @Test
    fun normalizeOnlyTrims() {
        assertEquals("ghp_abc", GithubConnector.normalize("  ghp_abc\n"))
    }
}
