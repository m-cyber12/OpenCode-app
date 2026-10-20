package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v9.38: the GitHub AGENTS.md writer. The contract the owner set - the model
 * must know to work GitHub-first WITHOUT being told - depends on this file
 * landing correctly in the project, so the three shapes (no file, our block
 * already present, a real user file) and idempotence are tested here.
 */
class GithubWorkspaceNotesTest {

    private fun tmp(name: String = "proj"): File {
        val dir = createTempDir(name)
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun createsTheFileWithTheOwnersContract() {
        val dir = tmp()
        assertTrue(GithubWorkspaceNotes.ensure(dir))
        val text = File(dir, "AGENTS.md").readText()
        assertTrue(text.contains("Commit AND push after EVERY logical change"))
        assertTrue(text.contains("very first"))
        assertTrue(text.contains("primary workspace"))
        // v9.40 (owner: "everything is pushed to master, the plan was a
        // separate dedicated branch per project"): the block must name the
        // repair and forbid the default branch.
        assertTrue(text.contains("NEVER push master or main"))
        assertTrue(text.contains("git rev-parse --abbrev-ref HEAD"))
        assertTrue(text.contains(GithubWorkspaceNotes.BEGIN))
        assertTrue(text.contains(GithubWorkspaceNotes.END))
    }

    @Test
    fun secondEnsureIsInertAndContentAfterEditsSurvives() {
        val dir = tmp()
        assertTrue(GithubWorkspaceNotes.ensure(dir))
        assertFalse("re-ensure must not rewrite a current file", GithubWorkspaceNotes.ensure(dir))
        // The model adds its own section; the app's next write must keep it
        // verbatim outside the block.
        val file = File(dir, "AGENTS.md")
        file.appendText("\n## My own notes\nkeep me\n")
        val before = file.readText()
        // Trailing user content changes nothing inside the markers, so the
        // re-ensure is INERT (byte-identical rebuild, no rewrite):
        assertFalse(GithubWorkspaceNotes.ensure(dir))
        val after = file.readText()
        assertTrue("user content outside the block survives", after.contains("## My own notes\nkeep me"))
        // exactly one block, still bounded
        assertEquals(1, Regex(Regex.escape(GithubWorkspaceNotes.BEGIN)).findAll(after).count())
        assertEquals(1, Regex(Regex.escape(GithubWorkspaceNotes.END)).findAll(after).count())
        assertEquals(before, after)
    }

    @Test
    fun appendsToAUserWrittenFileWithoutTouchingIt() {
        val dir = tmp()
        val file = File(dir, "AGENTS.md")
        file.writeText("# Real project conventions\n\nDo the thing.\n")
        assertTrue(GithubWorkspaceNotes.ensure(dir))
        val text = file.readText()
        assertTrue(text.startsWith("# Real project conventions\n\nDo the thing."))
        assertTrue(text.contains(GithubWorkspaceNotes.BEGIN))
        // appending again: nothing inside the markers changed, so the file is
        // left alone entirely (and the user text above is untouched).
        assertFalse(GithubWorkspaceNotes.ensure(dir))
        assertTrue(file.readText().startsWith("# Real project conventions\n\nDo the thing."))
        assertEquals(1, Regex(Regex.escape(GithubWorkspaceNotes.BEGIN)).findAll(file.readText()).count())
    }

    @Test
    fun refusesQuietlyWhenTheProjectDirIsGone() {
        val gone = File(tmp(), "not-here")
        assertFalse(GithubWorkspaceNotes.ensure(gone))
    }
}
