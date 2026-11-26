package ai.opencode.android.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMarkersTest {

    private val search =
        "{{visual {\"kind\":\"search\",\"query\":\"Gothic architecture flying buttresses\"," +
            "\"alt\":\"Flying buttresses on a Gothic cathedral\",\"count\":1} }}"

    @Test
    fun plainTextIsUnchanged() {
        val text = "A simple answer with {braces} and a link [x](https://example.com/a)."
        assertEquals(text, VisualMarkers.visibleText(text))
        assertEquals(emptyList<VisualMarkers.Request>(), VisualMarkers.parse(text).requests)
    }

    @Test
    fun completeSearchMarkerIsRemovedAndParsed() {
        val result = VisualMarkers.parse("Before.\n\n$search\n\nAfter.")
        assertEquals("Before.\n\n\n\nAfter.", result.visibleText)
        assertEquals(1, result.requests.size)
        val r = result.requests[0]
        assertEquals("search", r.kind)
        assertEquals("Gothic architecture flying buttresses", r.text)
        assertEquals("Flying buttresses on a Gothic cathedral", r.alt)
    }

    @Test
    fun generateMarkerUsesPrompt() {
        val m = "{{visual {\"kind\":\"generate\",\"prompt\":\"A labelled diagram of a cathedral\",\"alt\":\"Diagram\"} }}"
        val r = VisualMarkers.parse("x $m y").requests.single()
        assertEquals("generate", r.kind)
        assertEquals("A labelled diagram of a cathedral", r.text)
    }

    @Test
    fun incompleteMarkerAtTheEndIsHiddenWhileStreaming() {
        val partial = "Intro.\n\n{{visual {\"kind\":\"search\",\"query\":\"Gothic"
        assertEquals("Intro.\n\n", VisualMarkers.visibleText(partial))
        assertEquals(0, VisualMarkers.parse(partial).requests.size)
    }

    @Test
    fun markerWithOnlyPartOfTheClosingBracesIsStillHidden() {
        val almost = "Text. " + search.dropLast(1)
        assertEquals("Text. ", VisualMarkers.visibleText(almost))
        assertEquals(0, VisualMarkers.parse(almost).requests.size)
    }

    @Test
    fun partlyTypedOpeningAtTheEndIsHidden() {
        assertEquals("Text ", VisualMarkers.visibleText("Text {{vi"))
        assertEquals("Text {", VisualMarkers.visibleText("Text {"))
    }

    @Test
    fun malformedJsonIsHiddenAndDoesNotBreakTheText() {
        val bad = "Good. {{visual {\"kind\": oops} }} Still here."
        val r = VisualMarkers.parse(bad)
        assertEquals("Good.  Still here.", r.visibleText)
        assertEquals(0, r.requests.size)
    }

    @Test
    fun invalidFieldsAreRejected() {
        val noAlt = "{{visual {\"kind\":\"search\",\"query\":\"x\"} }}"
        val badKind = "{{visual {\"kind\":\"video\",\"query\":\"x\",\"alt\":\"y\"} }}"
        val noQuery = "{{visual {\"kind\":\"search\",\"alt\":\"y\"} }}"
        for (m in listOf(noAlt, badKind, noQuery)) {
            val r = VisualMarkers.parse(m)
            assertEquals("", r.visibleText)
            assertEquals(0, r.requests.size)
        }
    }

    @Test
    fun urlKeyIsIgnoredNeverUsed() {
        val m = "{{visual {\"kind\":\"search\",\"query\":\"x\",\"alt\":\"y\",\"url\":\"https://evil.example/a.png\"} }}"
        val r = VisualMarkers.parse(m).requests.single()
        assertEquals("x", r.text)
        assertTrue(!r.text.contains("evil"))
    }

    @Test
    fun markerInsideFencedCodeIsShownNotExecuted() {
        val text = "Example:\n```\n$search\n```\nDone."
        val r = VisualMarkers.parse(text)
        assertEquals(text, r.visibleText)
        assertEquals(0, r.requests.size)
    }

    @Test
    fun markerInsideInlineCodeIsShownNotExecuted() {
        val text = "Write `$search` to ask."
        assertEquals(text, VisualMarkers.visibleText(text))
        assertEquals(0, VisualMarkers.parse(text).requests.size)
    }

    @Test
    fun escapedMarkerIsShownNotExecuted() {
        val text = "Literal \\" + search
        assertEquals(text, VisualMarkers.visibleText(text))
        assertEquals(0, VisualMarkers.parse(text).requests.size)
    }

    @Test
    fun onlyTheFirstTwoValidRequestsAreKept() {
        val text = listOf(search, search, search).joinToString(" ")
        val r = VisualMarkers.parse(text)
        assertEquals(VisualMarkers.MAX_PER_MESSAGE, r.requests.size)
        assertEquals(1, r.dropped)
        assertEquals("  ", r.visibleText)
    }

    @Test
    fun braceInsideAStringDoesNotEndTheObject() {
        val m = "{{visual {\"kind\":\"search\",\"query\":\"a } b\",\"alt\":\"c\"} }}"
        val r = VisualMarkers.parse(m).requests.single()
        assertEquals("a } b", r.text)
    }

    @Test
    fun unknownModeKeyFallsBackToBalanced() {
        assertEquals(VisualMode.BALANCED, VisualMode.fromKey(null))
        assertEquals(VisualMode.BALANCED, VisualMode.fromKey("bogus"))
        assertEquals(VisualMode.OFF, VisualMode.fromKey("off"))
        assertEquals(VisualMode.RICH, VisualMode.fromKey("rich"))
    }
}
