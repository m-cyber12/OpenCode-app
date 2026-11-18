package ai.opencode.android.client.v2

import ai.opencode.android.ui.common.formatCost
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat footer reads tokens and cost from an assistant message. v2 sends them as
 * `tokens: {input, output, reasoning, cache}` and `cost` (both optional), and the
 * mapping must carry them across. A missing cost must never reach the screen as NaN.
 */
class V2MessageUsageTest {

    private fun assistant(extra: String) = JSONObject(
        """{"id":"msg_1","type":"assistant","agent":"build","model":{"providerID":"p","id":"m"},
           "content":[{"type":"text","text":"hi"}],"time":{"created":1000,"completed":5000}$extra}""",
    )

    @Test
    fun tokensAndCostAreCarriedAcrossWhenUpstreamSendsThem() {
        val out = V2Mapping.message(
            assistant(""","cost":0.0123,"tokens":{"input":120,"output":45,"reasoning":7,"cache":{"read":0,"write":0}}"""),
            "ses_1",
        )!!
        val info = out.getJSONObject("info")
        assertEquals(120L, info.getJSONObject("tokens").getLong("input"))
        assertEquals(45L, info.getJSONObject("tokens").getLong("output"))
        assertEquals(0.0123, info.getDouble("cost"), 1e-12)
    }

    @Test
    fun aMessageWithoutUsageGetsNoTokensAndNoCost() {
        val info = V2Mapping.message(assistant(""), "ses_1")!!.getJSONObject("info")
        assertFalse(info.has("tokens"))
        assertFalse(info.has("cost"))
    }

    @Test
    fun aNaNOrZeroCostIsNotShownAtAll() {
        assertEquals("", formatCost(Double.NaN))
        assertEquals("", formatCost(0.0))
        assertEquals("", formatCost(Double.POSITIVE_INFINITY))
        assertTrue(formatCost(0.0123).startsWith("$"))
        assertFalse(formatCost(0.0123).contains("NaN"))
    }
}
