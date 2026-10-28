package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v11 (owner, B.108): no trace of the owner's GitHub account anywhere in the app.
 * The learned login is scrubbed case-insensitively as a whole word, and the
 * `io.github.<x>.opencode` application id shows as `<app>`.
 */
class AccountRedactionTest {

    @Test
    fun appIdShowsAsAppPlaceholder() {
        assertEquals(
            "/data/user/0/<app>/files/x",
            AccountRedaction.scrub("/data/user/0/io.github.someone.opencode/files/x"),
        )
    }

    @Test
    fun learnedLoginIsScrubbedCaseInsensitivelyAsAWord() {
        AccountRedaction.rememberRepo("Acme-Dev/widgets")
        assertEquals("git clone https://github.com/<owner>/widgets", AccountRedaction.scrub("git clone https://github.com/acme-dev/widgets"))
        // a longer name that merely contains the login is left alone
        assertEquals("acme-devops", AccountRedaction.scrub("acme-devops"))
    }

    @Test
    fun rejectsStringsThatAreNotLogins() {
        AccountRedaction.remember("bad login!")
        assertEquals("bad login!", AccountRedaction.scrub("bad login!"))
    }
}
