package ai.opencode.android.github

import java.io.File
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // v9.29 (owner): the autoPush lifecycle test went with the feature -
    // pushing is now the MODEL's job via the bundled https-capable git,
    // which JGit-in-JVM cannot stand in for. Clone, branch creation and
    // branch stickiness remain covered below.

    @Test
    fun ensureProjectBranchPutsAStrayCheckoutBack() {
        // v9.28 (owner): every session of a GitHub project stays on
        // opencode/<project>. A model command may wander off; opening the
        // project or a new session puts the checkout back - and a dirty
        // conflict must abort harmlessly rather than destroy work.
        val origin = bareOrigin()
        val project = tmp.newFolder("site")
        GitSync.cloneAndBranch(
            origin.absolutePath, project, GitClone.projectBranch("site"), "unused",
        ) { }
        Git.open(project).use { git ->
            git.checkout().setName("main").call() // the stray move
            assertEquals("main", git.repository.branch)
        }
        GitSync.ensureProjectBranch(project)
        Git.open(project).use { git -> assertEquals("opencode/site", git.repository.branch) }
        // Already on it: a no-op. Null and non-git dirs: no-ops too.
        GitSync.ensureProjectBranch(project)
        GitSync.ensureProjectBranch(null)
        GitSync.ensureProjectBranch(tmp.newFolder("notgit"))
        Git.open(project).use { git -> assertEquals("opencode/site", git.repository.branch) }
    }

    @Test
    fun cloneIntoAFolderThatAlreadyHoldsAFileKeepsThatFile() {
        // Owner bug: "Destination path already exists and is not an empty
        // directory". The app writes AGENTS.md into a GitHub project folder
        // before the first clone, so the clone must land in a non-empty folder
        // and leave the file it finds there untouched.
        val origin = bareOrigin()
        val project = tmp.newFolder("notes")
        File(project, "AGENTS.md").writeText("# my notes\n")
        GitSync.cloneOnly(origin.absolutePath, project, "unused") { }
        assertTrue(File(project, "README.md").isFile)
        assertEquals("# my notes\n", File(project, "AGENTS.md").readText())
        Git.open(project).use { git -> assertEquals("main", git.repository.branch) }
    }

    @Test
    fun cloneAndBranchIntoANonEmptyFolderStillLandsOnTheProjectBranch() {
        val origin = bareOrigin()
        val project = tmp.newFolder("busy")
        File(project, "AGENTS.md").writeText("notes\n")
        GitSync.cloneAndBranch(
            origin.absolutePath, project, GitClone.projectBranch("busy"), "unused",
        ) { }
        assertTrue(File(project, "README.md").isFile)
        assertEquals("notes\n", File(project, "AGENTS.md").readText())
        Git.open(project).use { git -> assertEquals("opencode/busy", git.repository.branch) }
    }

    @Test
    fun aConflictingFileBlocksTheCloneAndIsNeverOverwritten() {
        // The repository ALSO has README.md; the folder's own README.md must
        // survive, and the half-made .git this attempt created must be removed.
        val origin = bareOrigin()
        val project = tmp.newFolder("clash")
        File(project, "README.md").writeText("mine, not theirs\n")
        var failure: Throwable? = null
        try {
            GitSync.cloneOnly(origin.absolutePath, project, "unused") { }
        } catch (t: Throwable) {
            failure = t
        }
        assertTrue("the conflict must be reported", failure != null)
        assertEquals("mine, not theirs\n", File(project, "README.md").readText())
        assertFalse(File(project, ".git").exists())
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
