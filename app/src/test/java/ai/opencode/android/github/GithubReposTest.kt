package ai.opencode.android.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.25: the pure half of the Arena-style repo UX. URL shapes and parsing
 * pinned, because a silent drift here turns the repo picker into an empty
 * list on the owner's device with nothing in CI to catch it.
 */
class GithubReposTest {

    @Test
    fun listUrlAsksForTheViewersReposNewestPushFirst() {
        assertEquals(
            "https://api.example/user/repos?per_page=100&sort=pushed",
            GithubRepos.listUrl("https://api.example/"),
        )
    }

    @Test
    fun headersCarryBearerTokenAndApiVersionNeverInUrl() {
        val h = GithubRepos.headers("tok123")
        assertEquals("Bearer tok123", h["Authorization"])
        assertEquals("application/vnd.github+json", h["Accept"])
        assertTrue(h.containsKey("X-GitHub-Api-Version"))
        // The token must never be URL material.
        assertFalse(GithubRepos.listUrl("https://api.example").contains("tok123"))
    }

    @Test
    fun parseReadsNameFullNameBranchAndVisibility() {
        val repos = GithubRepos.parseRepos(
            """[
              {"name":"CreatorsHub","full_name":"example-owner/CreatorsHub","default_branch":"main","private":false},
              {"name":"secret","full_name":"example-owner/secret","private":true}
            ]""",
        )
        assertEquals(2, repos.size)
        assertEquals("CreatorsHub", repos[0].name)
        assertEquals("example-owner/CreatorsHub", repos[0].fullName)
        assertEquals("main", repos[0].defaultBranch)
        assertFalse(repos[0].private)
        assertTrue(repos[1].private)
        // Absent default_branch falls back, never crashes.
        assertEquals("main", repos[1].defaultBranch)
    }

    @Test
    fun cloneUrlIsTheTokenFreeWebForm() {
        assertEquals(
            "https://web.example/example-owner/CreatorsHub.git",
            GithubRepos.cloneUrl("https://web.example/", "example-owner/CreatorsHub"),
        )
    }

    /** Owner (2026-11-23): the agent asked for a GitHub ID and email. The app now knows both. */
    @Test
    fun viewerFromGithubUserCarriesTheNoReplyEmail() {
        val v = GithubRepos.parseViewer("""{"login":"example-owner","id":12345,"name":"Owner"}""")
        assertEquals("example-owner", v?.login)
        assertEquals("12345+example-owner@users.noreply.github.com", v?.noReplyEmail)
    }

    @Test
    fun viewerIsNullWithoutALoginOrId() {
        assertEquals(null, GithubRepos.parseViewer("""{"message":"Bad credentials"}"""))
        assertEquals(null, GithubRepos.parseViewer("not json"))
    }

    /** An empty repository gets an empty README as its first commit, through the contents API. */
    @Test
    fun emptyRepoReadmeGoesThroughTheContentsApi() {
        assertEquals(
            "https://api.example/repos/example-owner/rub/contents/README.md",
            GithubRepos.readmeUrl("https://api.example/", "example-owner/rub"),
        )
        val body = org.json.JSONObject(GithubRepos.emptyReadmeBody())
        assertEquals("", body.getString("content"))
        assertEquals("Initial commit", body.getString("message"))
    }
}
