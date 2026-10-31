package ai.opencode.android.preview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewActionsTest {

    private fun ok(text: String): PreviewBatch {
        val r = PreviewActions.parse(text)
        assertTrue("expected a valid batch, got $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).batch
    }

    private fun bad(text: String): String {
        val r = PreviewActions.parse(text)
        assertTrue("expected a rejected batch, got $r", r is ParseResult.Invalid)
        return (r as ParseResult.Invalid).reason
    }

    /** What `evaluateJavascript` hands back: the script's string result, JSON-encoded. */
    private fun answer(o: JSONObject): String = JSONObject.quote(o.toString())

    @Test
    fun parsesEveryStepKind() {
        val b = ok(
            """{"id":"login-1","steps":[
                {"tap":{"text":"Sign in"}},
                {"type":{"selector":"#email","value":"a@b.c"}},
                {"scroll":{"y":400}},
                {"wait":{"ms":800}},
                {"tap":{"x":10,"y":20}},
                {"snapshot":true}]}""",
        )
        assertEquals("login-1", b.id)
        assertEquals(6, b.steps.size)
        assertEquals(PreviewStep.Tap(null, "Sign in", null, null), b.steps[0])
        assertEquals(PreviewStep.Type("#email", "a@b.c"), b.steps[1])
        assertEquals(PreviewStep.Scroll(400), b.steps[2])
        assertEquals(PreviewStep.Wait(800L), b.steps[3])
        assertEquals(PreviewStep.Tap(null, null, 10, 20), b.steps[4])
        assertEquals(PreviewStep.Snapshot, b.steps[5])
    }

    @Test
    fun rejectsBadBatches() {
        assertTrue(bad("nope").contains("not valid JSON"))
        assertTrue(bad("""{"id":"../x","steps":[{"snapshot":true}]}""").contains("id must be"))
        assertTrue(bad("""{"id":"a","steps":[]}""").contains("steps is empty"))
        assertTrue(bad("""{"id":"a"}""").contains("steps must be an array"))
        val many = (1..21).joinToString(",") { """{"snapshot":true}""" }
        assertTrue(bad("""{"id":"a","steps":[$many]}""").contains("at most 20 steps"))
        assertTrue(bad("""{"id":"a","steps":[{"click":{}}]}""").contains("unknown action \"click\""))
        assertTrue(bad("""{"id":"a","steps":[{"snapshot":true,"wait":{"ms":1}}]}""").contains("exactly one action"))
        assertTrue(bad("""{"id":"a","steps":[{"tap":{}}]}""").contains("tap needs"))
        assertTrue(bad("""{"id":"a","steps":[{"type":{"selector":"#a"}}]}""").contains("type needs"))
        assertTrue(bad("""{"id":"a","steps":[{"snapshot":false}]}""").contains("snapshot must be true"))
        val long = "x".repeat(PreviewActions.MAX_VALUE + 1)
        assertTrue(bad("""{"id":"a","steps":[{"type":{"selector":"#a","value":"$long"}}]}""").contains("type needs"))
    }

    @Test
    fun clampsWaitAndScroll() {
        val b = ok("""{"id":"a","steps":[{"wait":{"ms":999999}},{"scroll":{"y":-99999}}]}""")
        assertEquals(PreviewStep.Wait(PreviewActions.MAX_WAIT_MS), b.steps[0])
        assertEquals(PreviewStep.Scroll(-5000), b.steps[1])
    }

    @Test
    fun describesTheBatchForThePrompt() {
        val b = PreviewBatch(
            "a",
            listOf(PreviewStep.Tap(null, "Sign in", null, null), PreviewStep.Type("#email", "x")),
        )
        assertEquals("tap text=\"Sign in\"; type selector=#email", PreviewActions.describe(b))
    }

    @Test
    fun scriptsQuoteEveryAgentValue() {
        val hostile = "a\"); alert(1); //"
        val script = PreviewActions.tapScript(hostile, null, null, null)
        assertTrue(script.contains(JSONObject.quote(hostile)))
        assertFalse(script.contains("var sel=\"a\"); alert"))
        val typed = PreviewActions.typeScript("#q", hostile)
        assertTrue(typed.contains(JSONObject.quote(hostile)))
    }

    @Test
    fun outcomeReadsEachAnswer() {
        val tap = PreviewStep.Tap("#go", null, null, null)
        val good = PreviewActions.outcome(
            tap,
            answer(JSONObject().put("ok", true).put("hit", "button.primary").put("x", 180).put("y", 640)),
        )
        assertTrue(good.ok)
        assertEquals("-> hit=button.primary at 180,640", good.detail)

        val covered = PreviewActions.outcome(
            tap,
            answer(JSONObject().put("ok", false).put("reason", "obstructed by div.cookie-banner")),
        )
        assertFalse(covered.ok)
        assertEquals("obstructed by div.cookie-banner", covered.detail)

        assertEquals("no answer from the page", PreviewActions.outcome(tap, null).detail)
        assertEquals("no answer from the page", PreviewActions.outcome(tap, "null").detail)
        assertEquals(
            "unreadable answer from the page",
            PreviewActions.outcome(tap, JSONObject.quote("not json")).detail,
        )
    }

    @Test
    fun snapshotListsTappableElements() {
        val snap = PreviewStep.Snapshot
        val raw = answer(
            JSONObject()
                .put("ok", true)
                .put("total", 2)
                .put("title", "Demo")
                .put(
                    "elements",
                    org.json.JSONArray()
                        .put(JSONObject().put("tag", "button").put("text", "Sign in").put("id", "go").put("type", ""))
                        .put(JSONObject().put("tag", "input").put("text", "").put("id", "").put("type", "email")),
                ),
        )
        val r = PreviewActions.outcome(snap, raw)
        assertTrue(r.ok)
        assertTrue(r.detail.contains("tappable=2 shown=2"))
        assertTrue(r.detail.contains("button#go \"Sign in\""))
        assertTrue(r.detail.contains("input[email] \"\""))
    }

    @Test
    fun digestReportsFailureAndSkips() {
        val batch = PreviewBatch(
            "login-1",
            listOf(PreviewStep.Tap(null, "Sign in", null, null), PreviewStep.Snapshot, PreviewStep.Wait(10)),
        )
        val text = PreviewActions.digest(
            batch,
            listOf(StepResult(batch.steps[0].label, false, "obstructed by div.cookie-banner")),
            1930,
            listOf("ERROR app.js:3 boom"),
        )
        assertTrue(text.startsWith("ACT login-1 failed steps=0/3 ms=1930\n"))
        assertTrue(text.contains("tap text=\"Sign in\" FAILED: obstructed by div.cookie-banner"))
        assertTrue(text.contains("2 step(s) skipped after the first failure"))
        assertTrue(text.contains("CONSOLE errors=1"))
        assertTrue(text.contains("ERROR app.js:3 boom"))
    }

    @Test
    fun digestSaysOkWhenEveryStepPasses() {
        val batch = PreviewBatch("ok-1", listOf(PreviewStep.Wait(1), PreviewStep.Scroll(10)))
        val text = PreviewActions.digest(
            batch,
            listOf(StepResult("wait ms=1", true, ""), StepResult("scroll y=10", true, "-> scrollY=10")),
            12,
            emptyList(),
        )
        assertTrue(text.startsWith("ACT ok-1 ok steps=2/2 ms=12\n"))
        assertTrue(text.contains("CONSOLE errors=0"))
        assertFalse(text.contains("skipped"))
    }
}
