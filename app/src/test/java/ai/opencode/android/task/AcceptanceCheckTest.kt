package ai.opencode.android.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceCheckTest {

    private fun ok(text: String): AcceptanceCheck.Sheet {
        val r = AcceptanceCheck.parse(text)
        assertTrue("expected a valid sheet, got $r", r is AcceptanceCheck.Result.Ok)
        return (r as AcceptanceCheck.Result.Ok).sheet
    }

    private fun bad(text: String): String {
        val r = AcceptanceCheck.parse(text)
        assertTrue("expected a rejected sheet, got $r", r is AcceptanceCheck.Result.Invalid)
        return (r as AcceptanceCheck.Result.Invalid).reason
    }

    private val sheet = """
        {"request":"Improve login without breaking the API",
         "unclear":["Which API clients must keep working?"],
         "criteria":[
           {"id":"AC1","text":"POST /login returns 200 and a token","check":"npm test -- login","status":"pass","evidence":"12 passed"},
           {"id":"AC2","text":"Wrong password returns 401","status":"open"},
           {"id":"AC3","text":"Old /v1/users shape is unchanged","status":"fail","evidence":"field id missing"}]}
    """.trimIndent()

    @Test
    fun parsesTheSheet() {
        val s = ok(sheet)
        assertEquals("Improve login without breaking the API", s.request)
        assertEquals(listOf("Which API clients must keep working?"), s.unclear)
        assertEquals(3, s.criteria.size)
        assertEquals(AcceptanceCheck.Status.PASS, s.criteria[0].status)
        assertEquals("npm test -- login", s.criteria[0].check)
        assertEquals("", s.criteria[1].check)
        assertEquals(AcceptanceCheck.Status.FAIL, s.criteria[2].status)
    }

    @Test
    fun rejectsBadSheets() {
        assertTrue(bad("nope").contains("not valid JSON"))
        assertTrue(bad("""{"criteria":"x"}""").contains("criteria must be an array"))
        assertTrue(bad("""{"criteria":[]}""").contains("criteria is empty"))
        assertTrue(bad("""{"criteria":[{"id":"../x","text":"t","status":"open"}]}""").contains("id must be"))
        assertTrue(
            bad("""{"criteria":[{"id":"A","text":"t","status":"open"},{"id":"A","text":"u","status":"open"}]}""")
                .contains("duplicate id A"),
        )
        assertTrue(bad("""{"criteria":[{"id":"A","text":"","status":"open"}]}""").contains("text must be"))
        assertTrue(bad("""{"criteria":[{"id":"A","text":"t","status":"done"}]}""").contains("status must be open, pass or fail"))
        assertTrue(bad("""{"criteria":[{"id":"A","text":"t"}]}""").contains("status must be"))
        assertTrue(bad("""{"criteria":[1]}""").contains("criterion 1 is not an object"))
        val many = (1..31).joinToString(",") { """{"id":"C$it","text":"t","status":"open"}""" }
        assertTrue(bad("""{"criteria":[$many]}""").contains("at most 30 criteria"))
        val unclear = (1..11).joinToString(",") { "\"q$it\"" }
        assertTrue(
            bad("""{"unclear":[$unclear],"criteria":[{"id":"A","text":"t","status":"open"}]}""")
                .contains("at most 10 unclear items"),
        )
    }

    @Test
    fun passWithoutEvidenceIsUnverified() {
        val s = ok("""{"criteria":[{"id":"A","text":"t","status":"pass"}]}""")
        assertFalse(s.criteria[0].verified)
        assertFalse(s.complete)
        val text = AcceptanceCheck.report(s)
        assertTrue(text.contains("A UNVERIFIED t"))
        assertTrue(text.contains("unverified=1"))
    }

    @Test
    fun reportCountsAndVerdict() {
        val text = AcceptanceCheck.report(AcceptanceCheck.Result.Ok(ok(sheet)))
        val lines = text.lines()
        assertEquals(
            "ACCEPTANCE INCOMPLETE pass=1 open=1 fail=1 unverified=0 unclear=1",
            lines[0],
        )
        assertTrue(text.contains("  AC1 pass POST /login returns 200 and a token [check: npm test -- login] -- 12 passed"))
        assertTrue(text.contains("  AC2 open Wrong password returns 401"))
        assertTrue(text.contains("  AC3 FAIL Old /v1/users shape is unchanged -- field id missing"))
        assertTrue(text.contains("UNCLEAR (ask the user before you finish)"))
        assertTrue(text.contains("  - Which API clients must keep working?"))
    }

    @Test
    fun completeOnlyWhenEveryCriterionPassesWithEvidenceAndNothingIsUnclear() {
        val done = ok(
            """{"criteria":[
                {"id":"A","text":"one","status":"pass","evidence":"ok"},
                {"id":"B","text":"two","status":"pass","evidence":"ok"}]}""",
        )
        assertTrue(done.complete)
        assertTrue(AcceptanceCheck.report(done).startsWith("ACCEPTANCE COMPLETE pass=2 open=0 fail=0 unverified=0 unclear=0\n"))

        val withUnclear = ok(
            """{"unclear":["which DB?"],"criteria":[{"id":"A","text":"one","status":"pass","evidence":"ok"}]}""",
        )
        assertFalse(withUnclear.complete)
    }

    @Test
    fun invalidReportsTheReason() {
        assertEquals(
            "ACCEPTANCE INVALID: criteria.json is not valid JSON\n",
            AcceptanceCheck.report(AcceptanceCheck.parse("{")),
        )
    }
}
