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
              {"name":"CreatorsHub","full_name":"m-cyber12/CreatorsHub","default_branch":"main","private":false},
              {"name":"secret","full_name":"m-cyber12/secret","private":true}
            ]""",
        )
        assertEquals(2, repos.size)
        assertEquals("CreatorsHub", repos[0].name)
        assertEquals("m-cyber12/CreatorsHub", repos[0].fullName)
        assertEquals("main", repos[0].defaultBranch)
        assertFalse(repos[0].private)
        assertTrue(repos[1].private)
        // Absent default_branch falls back, never crashes.
        assertEquals("main", repos[1].defaultBranch)
    }

    @Test
    fun cloneUrlIsTheTokenFreeWebForm() {
        assertEquals(
            "https://web.example/m-cyber12/CreatorsHub.git",
            GithubRepos.cloneUrl("https://web.example/", "m-cyber12/CreatorsHub"),
        )
    }
}
