package ai.opencode.android.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v9.36: the REPORT digest is the agent's cheap layout verdict - the thing
 * that stands between "did my CSS work?" and a multi-MB screenshot read that
 * rides on every later step. Its formatting contract is pinned so the brief
 * can promise the exact words.
 */
class RenderProbeTest {

    @Test
    fun digestFormatsProbeNumbersIntoOneLine() {
        val raw = """{"viewport":[412,915],"doc":[412,2350],"overflowX":0,""" +
            """"imgs":4,"totalImgs":5,"broken":1,"text":1810}"""
        assertEquals(
            "REPORT viewport=412x915 doc=412x2350 overflowX=0px imgs=4/5 broken=1 text=1810ch frame=changed",
            RenderProbe.digest(raw, "changed"),
        )
    }

    @Test
    fun digestOmitsFrameWhenAbsent() {
        val raw = """{"viewport":[412,915],"doc":[412,915],"overflowX":37,"imgs":0,"totalImgs":0,"broken":0,"text":12}"""
        assertEquals(
            "REPORT viewport=412x915 doc=412x915 overflowX=37px imgs=0/0 broken=0 text=12ch",
            RenderProbe.digest(raw),
        )
    }

    @Test
    fun brokenOrMissingProbeAlwaysYieldsALine() {
        // A silent miss is what let the recapture spiral start; the agent
        // must ALWAYS get a line to read, even from garbage.
        assertEquals("REPORT unavailable", RenderProbe.digest(null))
        assertEquals("REPORT unavailable", RenderProbe.digest("not json at all"))
        assertEquals("REPORT unavailable", RenderProbe.digest("""{"error":"boom"}"""))
    }

    @Test
    fun probeScriptAnswersEveryDigestField() {
        for (key in listOf("viewport", "doc", "overflowX", "imgs", "totalImgs", "broken", "text")) {
            assertEquals("JS must produce $key", true, RenderProbe.JS.contains("$key:"))
        }
    }
}
