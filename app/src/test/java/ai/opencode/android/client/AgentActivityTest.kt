package ai.opencode.android.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The busy flag the runtime notification reads, and the Pause that stops a turn. */
class AgentActivityTest {

    private class Owner

    @After
    fun reset() {
        AgentActivity.release(owner)
    }

    private val owner = Owner()

    @Test
    fun reportingBusyIsVisibleAndReleasingClearsIt() {
        AgentActivity.report(owner, true) {}
        assertTrue(AgentActivity.busy.value)
        AgentActivity.release(owner)
        assertFalse(AgentActivity.busy.value)
    }

    @Test
    fun aReplacedOwnerCannotClearTheCurrentOne() {
        val other = Owner()
        AgentActivity.report(owner, true) {}
        AgentActivity.release(other)
        assertTrue(AgentActivity.busy.value)
    }

    @Test
    fun pauseStopsTheTurnOnlyWhileBusy() {
        var stopped = 0
        AgentActivity.report(owner, false) { stopped++ }
        assertFalse(AgentActivity.pauseTurns())
        assertEquals(0, stopped)

        AgentActivity.report(owner, true) { stopped++ }
        assertTrue(AgentActivity.pauseTurns())
        assertEquals(1, stopped)
    }
}
