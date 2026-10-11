package ai.opencode.android.ui

import ai.opencode.android.ui.chat.EmptyChatCopy
import ai.opencode.android.ui.chat.questionDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.35 (owner): the empty chat's copy is pinned here - the 30 headlines
 * verbatim, one per day, the 30 tips, the subtitle - plus the plan-path
 * sanitizer for the question card.
 */
class EmptyChatCopyTest {

    @Test
    fun headlinesAreTheOwnersThirtyVerbatim() {
        assertEquals(30, EmptyChatCopy.HEADLINES.size)
        assertEquals("Let's build something great.", EmptyChatCopy.HEADLINES.first())
        assertEquals("Today is a good day to build.", EmptyChatCopy.HEADLINES.last())
        assertTrue(EmptyChatCopy.HEADLINES.contains("What are we creating today?"))
        assertTrue(EmptyChatCopy.HEADLINES.contains("From bug to breakthrough."))
        // No blank or duplicated lines.
        assertTrue(EmptyChatCopy.HEADLINES.all { it.isNotBlank() })
        assertEquals(EmptyChatCopy.HEADLINES.size, EmptyChatCopy.HEADLINES.toSet().size)
    }

    @Test
    fun oneHeadlinePerDayRotating() {
        assertEquals(EmptyChatCopy.HEADLINES[0], EmptyChatCopy.headlineFor(0))
        assertEquals(EmptyChatCopy.HEADLINES[1], EmptyChatCopy.headlineFor(1))
        assertEquals(EmptyChatCopy.HEADLINES[29], EmptyChatCopy.headlineFor(29))
        // The next day rotates; day 30 is day 0 again.
        assertEquals(EmptyChatCopy.HEADLINES[0], EmptyChatCopy.headlineFor(30))
        assertEquals(EmptyChatCopy.HEADLINES[5], EmptyChatCopy.headlineFor(35))
        // Stable within the day.
        assertEquals(EmptyChatCopy.headlineFor(20781), EmptyChatCopy.headlineFor(20781))
    }

    @Test
    fun tipsAndSubtitle() {
        assertEquals(30, EmptyChatCopy.TIPS.size)
        assertTrue(EmptyChatCopy.TIPS.all { it.isNotBlank() && it.length < 120 })
        assertTrue(EmptyChatCopy.SUBTITLE.isNotBlank())
    }

    @Test
    fun planExitQuestionHidesThePlanFilePath() {
        val raw = "Plan at data/user/0/io.github.mcyber12.opencode/files/xdg/data/opencode/plans/" +
            "1791712569939-quiet-star.md is complete. Would you like to switch to the " +
            "build agent and start implementing?"
        val shown = questionDisplayText(raw)
        assertTrue(shown.startsWith("The plan is ready."))
        assertTrue(shown.contains("Would you like to switch to the build agent"))
        assertFalse(shown.contains("quiet-star.md"))
        assertFalse(shown.contains("plans/"))
    }

    @Test
    fun otherQuestionsRenderVerbatim() {
        val raw = "Do you have real content (name, projects, links) to use?"
        assertEquals(raw, questionDisplayText(raw))
    }
}
