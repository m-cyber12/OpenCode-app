package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for tool-call parsing: the family an unfamiliar tool name maps to, and
 * what is pulled out of upstream's opaque `state.metadata` map.
 *
 * The metadata shapes come from the pinned upstream tool implementations
 * (`tool/shell.ts` -> output/exit/truncated/outputPath, `tool/write.ts` and
 * `tool/edit.ts` -> diff/filediff/diagnostics), which is why this is parsing rather
 * than invention - and why an unexpected shape must degrade to empty fields instead
 * of throwing inside a RecyclerView-sized list of tool cards.
 */
class ToolMetaParserTest {

    @Test
    fun builtinToolNamesMapOntoFamilies() {
        assertEquals(ToolKind.SHELL, ToolKinds.of("bash"))
        assertEquals(ToolKind.SHELL, ToolKinds.of("shell"))
        assertEquals(ToolKind.FILE_READ, ToolKinds.of("read"))
        assertEquals(ToolKind.FILE_WRITE, ToolKinds.of("write"))
        assertEquals(ToolKind.FILE_EDIT, ToolKinds.of("edit"))
        assertEquals(ToolKind.FILE_EDIT, ToolKinds.of("multiedit"))
        assertEquals(ToolKind.SEARCH, ToolKinds.of("grep"))
        assertEquals(ToolKind.SEARCH, ToolKinds.of("glob"))
        assertEquals(ToolKind.WEB, ToolKinds.of("webfetch"))
        assertEquals(ToolKind.TASK, ToolKinds.of("task"))
        assertEquals(ToolKind.TODO, ToolKinds.of("todowrite"))
        assertEquals(ToolKind.PLAN, ToolKinds.of("plan_enter"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndExactBeforePattern() {
        assertEquals(ToolKind.SHELL, ToolKinds.of("BASH"))
        assertEquals(ToolKind.FILE_READ, ToolKinds.of("  Read  "))
        assertEquals(ToolKind.FILE_WRITE, ToolKinds.of("WRITE"))
        // "todowrite" contains "write": the exact builtin name must win, or a task
        // list update would be labelled as a file write.
        assertEquals(ToolKind.TODO, ToolKinds.of("TODOWRITE"))
        assertEquals(ToolKind.TODO, ToolKinds.of("todoread"))
    }

    @Test
    fun underscoredNamesFromMcpServersAreMcp() {
        // Upstream registers MCP tools as `<server>_<tool>` (Phase 3 gate evidence:
        // `gates-mcp_echo`), and the raw name is always shown next to this label.
        assertEquals(ToolKind.MCP, ToolKinds.of("github_create_issue"))
        assertEquals(ToolKind.MCP, ToolKinds.of("linear.list_issues"))
        assertEquals(ToolKind.MCP, ToolKinds.of("gates-mcp_echo"))
    }

    @Test
    fun anythingUnrecognisedIsOtherAndStillRendered() {
        assertEquals(ToolKind.OTHER, ToolKinds.of("brandnewtool"))
        assertEquals(ToolKind.OTHER, ToolKinds.of("question"))
        assertEquals(ToolKind.OTHER, ToolKinds.of(""))
    }

    @Test
    fun shellMetadataCarriesOutputExitAndTruncation() {
        val meta = ToolMetaParser.parse(
            """{"output":"total 8\ndrwxr-xr-x 2 u u 4096 .","exit":0,"truncated":false}""",
        )
        assertTrue(meta.output.startsWith("total 8"))
        assertTrue("the newline in the output must survive parsing", meta.output.contains("\n"))
        assertEquals(0, meta.exit)
        assertFalse(meta.truncated)
        assertFalse(meta.hasDiff)
        assertEquals("", meta.diff)
    }

    @Test
    fun truncatedShellOutputPointsAtTheFullFile() {
        val meta = ToolMetaParser.parse("""{"output":"first line","truncated":true,"outputPath":"/tmp/o.txt","exit":1}""")
        assertTrue(meta.truncated)
        assertEquals("/tmp/o.txt", meta.outputPath)
        assertEquals(1, meta.exit)
    }

    @Test
    fun aMissingExitStaysNullInsteadOfPretendingSuccess() {
        val meta = ToolMetaParser.parse("""{"output":"hi"}""")
        assertNull("no exit field must not read as exit 0", meta.exit)
        val explicitNull = ToolMetaParser.parse("""{"output":"hi","exit":null}""")
        assertNull(explicitNull.exit)
    }

    @Test
    fun writeAndEditMetadataCarryTheDiffAndItsCounts() {
        val meta = ToolMetaParser.parse(
            """
            {
              "diff": "@@ -1 +1 @@\n-old\n+new\n",
              "filediff": {"file":"src/A.kt","patch":"@@ -1 +1 @@\n-old\n+new\n","additions":3,"deletions":1},
              "diagnostics": {"src/A.kt": [{"message":"unused"}]}
            }
            """.trimIndent(),
        )
        assertTrue(meta.hasDiff)
        assertTrue(meta.diff.contains("+new"))
        assertEquals("src/A.kt", meta.diffFile)
        assertEquals(3, meta.additions)
        assertEquals(1, meta.deletions)
        assertEquals(1, meta.diagnosticsCount)
    }

    @Test
    fun aDiffOnlyInsideFilediffIsStillADiff() {
        val meta = ToolMetaParser.parse("""{"filediff":{"file":"a.txt","patch":"+x","additions":1,"deletions":0}}""")
        assertTrue(meta.hasDiff)
        assertEquals("+x", meta.diff)
        assertEquals("a.txt", meta.diffFile)
        assertEquals(1, meta.additions)
        assertEquals(0, meta.deletions)
        assertEquals(0, meta.diagnosticsCount)
    }

    @Test
    fun diagnosticsWithoutADiffAreStillCounted() {
        val meta = ToolMetaParser.parse("""{"diagnostics":{"a.kt":[1,2],"b.kt":[3]}}""")
        assertEquals(2, meta.diagnosticsCount)
        assertTrue(meta.diagnosticsCount > 0)
        assertFalse(meta.hasDiff)
    }

    @Test
    fun readMetadataTruncationIsReported() {
        assertTrue(ToolMetaParser.parse("""{"truncated":true}""").truncated)
        assertFalse(ToolMetaParser.parse("""{"truncated":false}""").truncated)
    }

    @Test
    fun blankGarbageAndNullsNeverThrow() {
        for (raw in listOf("", "   ", "null", "not json", "{}", "[]", """{"exit":"zero"}""")) {
            val meta = ToolMetaParser.parse(raw)
            assertEquals("", meta.output)
            assertEquals("", meta.diff)
            assertEquals("", meta.diffFile)
            assertFalse(meta.hasDiff)
            assertFalse(meta.truncated)
            assertEquals(0, meta.additions)
            assertEquals(0, meta.deletions)
            assertEquals(0, meta.diagnosticsCount)
        }
        // "exit":"zero" is present but not a number: org.json coerces to 0, which is
        // upstream's own behaviour for a malformed field - asserted, not assumed.
        assertEquals(0, ToolMetaParser.parse("""{"exit":"zero"}""").exit)
    }

    @Test
    fun numbersOfAnyJsonTypeStillParse() {
        val meta = ToolMetaParser.parse("""{"filediff":{"additions":"4","deletions":2.0},"exit":"3"}""")
        assertEquals(4, meta.additions)
        assertEquals(2, meta.deletions)
        assertEquals(3, meta.exit)
    }
}
