package ai.opencode.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the syntax tokeniser.
 *
 * The invariant that matters most is asserted over every fixture: the
 * concatenation of the spans is byte-equal to the input. A highlighter that
 * silently drops or rewrites characters would be worse than no highlighter,
 * because it would misreport what the agent actually ran or wrote.
 */
class CodeHighlightTest {

    private fun spans(language: String, source: String) = CodeHighlight.tokenize(language, source)

    private fun assertLossless(language: String, source: String): List<CodeSpan> {
        val out = spans(language, source)
        assertEquals("tokeniser must not change the text ($language)", source, CodeHighlight.text(out))
        return out
    }

    @Test
    fun kotlinKeywordsNumbersAndCalls() {
        val out = assertLossless("kotlin", "val total = compute(42)")
        assertTrue(out.any { it.token == CodeToken.KEYWORD && it.text == "val" })
        assertTrue(out.any { it.token == CodeToken.FUNCTION && it.text == "compute" })
        assertTrue(out.any { it.token == CodeToken.NUMBER && it.text == "42" })
    }

    @Test
    fun lineAndBlockComments() {
        assertTrue(assertLossless("kotlin", "// note").all { it.token == CodeToken.COMMENT })
        assertTrue(assertLossless("kotlin", "/* a */").all { it.token == CodeToken.COMMENT })
        assertTrue(assertLossless("bash", "# note").all { it.token == CodeToken.COMMENT })
        assertTrue(assertLossless("sql", "-- note").all { it.token == CodeToken.COMMENT })
        assertTrue(assertLossless("xml", "<!-- note -->").all { it.token == CodeToken.COMMENT })
    }

    @Test
    fun commentDoesNotSwallowTheNextLine() {
        val out = assertLossless("kotlin", "// note\nval x = 1")
        assertTrue(out.any { it.token == CodeToken.KEYWORD && it.text == "val" })
    }

    @Test
    fun stringsKeepTheirEscapes() {
        val out = assertLossless("kotlin", "val s = \"a\\\"b\"")
        assertTrue(out.any { it.token == CodeToken.STRING && it.text == "\"a\\\"b\"" })
    }

    @Test
    fun pythonTripleQuotedStringIsOneSpan() {
        val source = "x = \"\"\"doc\nline\"\"\""
        val out = assertLossless("python", source)
        assertTrue(out.any { it.token == CodeToken.STRING && it.text.contains("doc\nline") })
    }

    @Test
    fun javascriptTemplateLiteralIsAString() {
        val out = assertLossless("javascript", "const s = `a\${b}c`")
        assertTrue(out.any { it.token == CodeToken.STRING && it.text.startsWith("`") })
    }

    @Test
    fun capitalisedIdentifiersAreTypes() {
        val out = assertLossless("kotlin", "val f = File(path)")
        assertTrue(out.any { it.token == CodeToken.TYPE && it.text == "File" })
    }

    @Test
    fun jsonKeysAreTypesAndLiteralsAreKeywords() {
        val out = assertLossless("json", "{\"name\": \"x\", \"ok\": true}")
        assertTrue(out.any { it.token == CodeToken.TYPE && it.text == "\"name\"" })
        assertTrue(out.any { it.token == CodeToken.KEYWORD && it.text == "true" })
        assertTrue(out.any { it.token == CodeToken.STRING && it.text == "\"x\"" })
    }

    @Test
    fun diffLinesAreColouredBySign() {
        val source = "--- a/Foo.kt\n+++ b/Foo.kt\n@@ -1,2 +1,3 @@\n+added\n-removed\n context"
        val out = assertLossless("diff", source)
        val byLine = out.filter { it.text.isNotEmpty() && it.text != "\n" }
        assertTrue(byLine.any { it.token == CodeToken.STRING && it.text == "+added" })
        assertTrue(byLine.any { it.token == CodeToken.NUMBER && it.text == "-removed" })
        assertTrue(byLine.any { it.token == CodeToken.FUNCTION && it.text.startsWith("@@") })
        assertTrue(byLine.any { it.token == CodeToken.TYPE && it.text.startsWith("---") })
    }

    @Test
    fun unterminatedStringStopsAtTheNewline() {
        val out = assertLossless("kotlin", "\"abc\ndef")
        assertTrue(out.any { it.token == CodeToken.STRING && it.text == "\"abc" })
        assertTrue(out.any { it.token == CodeToken.PLAIN && it.text.contains("def") })
    }

    @Test
    fun punctuationRunsMerge() {
        val out = assertLossless("kotlin", "a => b")
        assertTrue(out.any { it.token == CodeToken.PUNCTUATION && it.text.contains("=") })
    }

    @Test
    fun shellVariablesAndCommands() {
        val out = assertLossless("bash", "echo \"\$PATH\" | grep x")
        assertTrue(out.any { it.token == CodeToken.KEYWORD && it.text == "echo" })
        assertTrue(out.any { it.token == CodeToken.STRING })
    }

    @Test
    fun languageAliasesResolve() {
        assertEquals("typescript", CodeHighlight.language("ts"))
        assertEquals("bash", CodeHighlight.language("sh"))
        assertEquals("python", CodeHighlight.language("py"))
        assertEquals("kotlin", CodeHighlight.language("Kotlin"))
        assertEquals("kotlin", CodeHighlight.language("kts"))
        assertEquals("plain", CodeHighlight.language(""))
        assertEquals("diff", CodeHighlight.language("patch"))
    }

    @Test
    fun supportedLanguagesAreReportedHonestly() {
        assertTrue(CodeHighlight.isSupported("kotlin"))
        assertTrue(CodeHighlight.isSupported("bash"))
        assertTrue(CodeHighlight.isSupported("diff"))
        assertTrue(CodeHighlight.isSupported(""))
        assertFalse(CodeHighlight.isSupported("brainfuck"))
    }

    @Test
    fun unknownLanguageStillTokenisesStringsAndNumbers() {
        val out = assertLossless("brainfuck", "x = 12 \"s\"")
        assertTrue(out.any { it.token == CodeToken.NUMBER && it.text == "12" })
        assertTrue(out.any { it.token == CodeToken.STRING && it.text == "\"s\"" })
    }

    @Test
    fun emptyAndWhitespaceInputsAreLossless() {
        assertEquals("", CodeHighlight.text(spans("kotlin", "")))
        assertEquals("   ", CodeHighlight.text(spans("kotlin", "   ")))
        assertEquals("\n", CodeHighlight.text(spans("kotlin", "\n")))
    }

    @Test
    fun realFixturesStayByteExact() {
        val fixtures = mapOf(
            "kotlin" to "fun main() {\n    val names = listOf(\"a\", \"b\")\n    for (n in names) println(n)\n}",
            "bash" to "set -euo pipefail\nfor f in \$(ls); do\n  echo \"\$f\"\ndone\n",
            "python" to "def f(x):\n    # comment\n    return x + 1\n",
            "json" to "{\n  \"a\": [1, 2],\n  \"b\": null\n}",
            "yaml" to "key: value\nlist:\n  - one\n  - two\n",
            "typescript" to "export type A = { b: string }\nconst f = async () => await g()\n",
            "diff" to "diff --git a/x b/x\nindex 123..456 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n",
            "go" to "func main() {\n    fmt.Println(\"hi\")\n}\n",
            "rust" to "fn main() {\n    let x: Option<u32> = Some(1);\n}\n",
            "sql" to "SELECT a, b FROM t WHERE a = 1 -- comment\nORDER BY a;\n",
        )
        for ((language, source) in fixtures) {
            val out = CodeHighlight.tokenize(language, source)
            assertEquals("byte-exact for $language", source, CodeHighlight.text(out))
            assertTrue("at least two roles for $language", out.map { it.token }.distinct().size >= 2)
        }
    }
}
