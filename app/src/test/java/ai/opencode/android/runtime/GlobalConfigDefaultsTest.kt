package ai.opencode.android.runtime

import ai.opencode.android.client.v2.JsoncEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlobalConfigDefaultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun todoOf(file: File): Any? =
        JsoncEdit.parseObject(file.readText()).optJSONObject("experimental")?.opt("todo")

    @Test
    fun aFreshFolderGetsTheTodoToolOn() {
        val dir = File(tmp.root, "opencode")
        assertTrue(GlobalConfigDefaults.ensureTodoTool(dir))
        assertEquals(true, todoOf(File(dir, "opencode.jsonc")))
    }

    @Test
    fun anExistingFileKeepsItsCommentsAndOtherKeys() {
        val dir = tmp.newFolder("opencode")
        val file = File(dir, "opencode.jsonc")
        file.writeText(
            """
            {
              // the user's own note
              "model": "x/y",
              "experimental": { "other": 1 }
            }
            """.trimIndent() + "\n",
        )
        assertTrue(GlobalConfigDefaults.ensureTodoTool(dir))
        val text = file.readText()
        assertTrue("comment kept", text.contains("// the user's own note"))
        val root = JsoncEdit.parseObject(text)
        assertEquals("x/y", root.getString("model"))
        assertEquals(1, root.getJSONObject("experimental").getInt("other"))
        assertEquals(true, todoOf(file))
    }

    @Test
    fun anExplicitChoiceInTheFileWins() {
        val dir = tmp.newFolder("opencode")
        val file = File(dir, "opencode.json")
        file.writeText("""{"experimental":{"todo":false}}""")
        assertFalse(GlobalConfigDefaults.ensureTodoTool(dir))
        assertEquals(false, todoOf(file))
    }

    @Test
    fun aSecondCallChangesNothing() {
        val dir = tmp.newFolder("opencode")
        assertTrue(GlobalConfigDefaults.ensureTodoTool(dir))
        val before = File(dir, "opencode.jsonc").readText()
        assertFalse(GlobalConfigDefaults.ensureTodoTool(dir))
        assertEquals(before, File(dir, "opencode.jsonc").readText())
    }
}
