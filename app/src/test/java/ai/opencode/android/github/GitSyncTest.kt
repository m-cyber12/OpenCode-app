package ai.opencode.android.github

import java.io.File
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v9.27: REAL round-trip coverage for the app's GitHub transport (JGit) -
 * clone, project branch, needs-push detection, push, re-push - against
 * local repositories. The exec-git design this replaces could never be
 * exercised off-device (the bundled git is NO_CURL anyway); this suite
 * runs the exact code the device runs, minus only the TLS socket.
 */
class GitSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A bare "GitHub" with one commit on `main`, plus its seed work repo. */
    private fun bareOrigin(): File {
        val bare = tmp.newFolder("origin.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        val seed = tmp.newFolder("seed")
        Git.init().setInitialBranch("main").setDirectory(seed).call().use { git ->
            File(seed, "README.md").writeText("hello\n")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("init").setCommitter("t", "t@test").setSign(false).call()
            git.remoteAdd().setName("origin").setUri(
                org.eclipse.jgit.transport.URIish(bare.absolutePath),
            ).call()
            git.push().setRemote("origin").add("main").call()
        }
        return bare
    }

    @Test
    fun oneBranchPerProjectNamedAfterIt() {
        // v9.26 (owner): the WHOLE project - every chat - works on one
        // `opencode/<project>` branch created at clone time.
        assertEquals("opencode/creatorshub", GitClone.projectBranch("CreatorsHub"))
    }

    @Test
    fun cloneLandsOnTheProjectBranchWithTheFilesPresent() {
        val origin = bareOrigin()
        val project = tmp.newFolder("site") // ProjectStore.create() makes an EMPTY dir
        val lines = mutableListOf<String>()
        GitSync.cloneAndBranch(
            cloneUrl = origin.absolutePath,
            targetDir = project,
            branch = GitClone.projectBranch("site"),
            token = "unused-for-local-transport",
        ) { line -> lines.add(line) }
        assertTrue(File(project, "README.md").isFile)
        Git.open(project).use { git ->
            assertEquals("opencode/site", git.repository.branch)
            // The on-disk origin URL is token-free by construction.
            assertFalse(
                git.repository.config.getString("remote", "origin", "url").contains("unused"),
            )
        }
        assertTrue(GitSync.isGithubProject(project).not()) // local origin, not github.com
    }

    @Test
    fun autoPushLifecycleFirstPushThenUpToDateThenNewCommit() {
        val origin = bareOrigin()
        val project = tmp.newFolder("app")
        GitSync.cloneAndBranch(
            origin.absolutePath, project, GitClone.projectBranch("app"), "unused",
        ) { }
        // Fresh project branch: never pushed -> needs a push even with no new work.
        assertTrue(GitSync.needsPush(project))
        assertEquals("pushed opencode/app", GitSync.pushCurrentBranch(project, "unused"))
        Git.open(File(origin.path)).use { bare ->
            assertNotNull(bare.repository.resolve("refs/heads/opencode/app"))
        }
        // Upstream recorded -> in-sync branch does not spam the network.
        assertFalse(GitSync.needsPush(project))
        assertEquals("up to date", GitSync.pushCurrentBranch(project, "unused"))
        // The agent commits (bundled git on device; JGit stands in here)...
        Git.open(project).use { git ->
            File(project, "work.txt").writeText("done\n")
            git.add().addFilepattern("work.txt").call()
            git.commit().setMessage("agent work").setCommitter("t", "t@test").setSign(false).call()
        }
        // ...and the post-reply auto-push moves the remote.
        assertTrue(GitSync.needsPush(project))
        assertEquals("pushed opencode/app", GitSync.pushCurrentBranch(project, "unused"))
        Git.open(File(origin.path)).use { bare ->
            val tip = bare.repository.resolve("refs/heads/opencode/app")
            val commit = bare.repository.parseCommit(tip)
            assertEquals("agent work", commit.shortMessage)
        }
    }

    @Test
    fun githubOriginDetectionReadsTheRemoteUrl() {
        val project = tmp.newFolder("gh")
        Git.init().setInitialBranch("main").setDirectory(project).call().use { git ->
            git.remoteAdd().setName("origin").setUri(
                org.eclipse.jgit.transport.URIish("https://github.com/owner/repo.git"),
            ).call()
        }
        assertTrue(GitSync.isGithubProject(project))
        assertFalse(GitSync.isGithubProject(tmp.newFolder("plain")))
        assertFalse(GitSync.isGithubProject(null))
    }
}
