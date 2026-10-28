package ai.opencode.android.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v11 (owner, B.107): the Settings skills card reads and writes skills through this. */
class SkillCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: File, text: String): File {
        path.parentFile?.mkdirs()
        path.writeText(text)
        return path
    }

    @Test
    fun scanFindsProjectSkillsBeforeGlobalOnes() {
        val config = tmp.newFolder("config")
        val project = tmp.newFolder("proj")
        write(
            File(project, ".opencode/skills/review/SKILL.md"),
            "---\nname: review\ndescription: Review a diff\n---\nbody\n",
        )
        write(
            File(config, "skills/notes/SKILL.md"),
            "---\nname: notes\ndescription: Keep notes\n---\n",
        )
        val found = SkillCatalog.scan(project, config)
        assertEquals(listOf("review", "notes"), found.map { it.name })
        assertEquals(listOf("project", "global"), found.map { it.scope })
        assertEquals("Review a diff", found[0].description)
    }

    @Test
    fun missingFoldersAreJustEmpty() {
        val config = tmp.newFolder("empty-config")
        assertTrue(SkillCatalog.scan(null, config).isEmpty())
        assertTrue(SkillCatalog.scan(tmp.newFolder("bare"), config).isEmpty())
    }

    @Test
    fun aHeaderlessSkillTakesItsFolderNameAndEmptyDescription() {
        val config = tmp.newFolder("cfg")
        write(File(config, "skill/plain/SKILL.md"), "Just instructions, no header.\n")
        val only = SkillCatalog.scan(null, config).single()
        assertEquals("plain", only.name)
        assertEquals("", only.description)
    }

    @Test
    fun quotedValuesAreUnquotedAndEscapesUndone() {
        val fm = SkillCatalog.frontmatter(
            "---\nname: \"quoted-name\"\ndescription: \"Use when: a \\\"quote\\\" appears\"\n---\n",
        )
        assertEquals("quoted-name", fm["name"])
        assertEquals("Use when: a \"quote\" appears", fm["description"])
    }

    @Test
    fun aHeaderWithoutItsClosingFenceIsIgnored() {
        assertTrue(SkillCatalog.frontmatter("---\nname: open\n").isEmpty())
        assertTrue(SkillCatalog.frontmatter("no header at all").isEmpty())
    }

    @Test
    fun createWritesAHeaderThatParsesBackExactly() {
        val config = tmp.newFolder("global")
        val description = "Use when: the user asks for a changelog entry"
        val made = SkillCatalog.create(config, "Changelog Writer!", description, "Write one line.")
        assertTrue(made is SkillCatalog.Created.Ok)
        val file = (made as SkillCatalog.Created.Ok).file
        assertEquals(File(config, "skills/changelog-writer/SKILL.md").absolutePath, file.absolutePath)
        val back = SkillCatalog.parse(file, "global")
        assertEquals("changelog-writer", back.name)
        assertEquals(description, back.description)
        assertTrue(file.readText().contains("Write one line."))
    }

    @Test
    fun createNeverOverwritesAnExistingSkill() {
        val config = tmp.newFolder("global2")
        SkillCatalog.create(config, "docs", "first", "one")
        val again = SkillCatalog.create(config, "docs", "second", "two")
        assertEquals("docs", (again as SkillCatalog.Created.Exists).name)
        assertEquals("first", SkillCatalog.parse(File(config, "skills/docs/SKILL.md"), "global").description)
    }

    @Test
    fun createRefusesAnEmptyNameOrDescription() {
        val config = tmp.newFolder("global3")
        assertEquals("name", (SkillCatalog.create(config, "  !!  ", "x", "") as SkillCatalog.Created.Invalid).reason)
        assertEquals(
            "description",
            (SkillCatalog.create(config, "ok", "  \n ", "") as SkillCatalog.Created.Invalid).reason,
        )
        assertFalse(File(config, "skills").exists())
    }

    @Test
    fun sanitizeKeepsOnlyFolderSafeCharacters() {
        assertEquals("a-b-c", SkillCatalog.sanitizeName("  A/B  c "))
        assertEquals("", SkillCatalog.sanitizeName("***"))
    }
}
