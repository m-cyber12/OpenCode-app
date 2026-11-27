package ai.opencode.android.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAgentsTest {

    private val tester = Worker(name = "Tester", duty = "Write and run the unit tests", model = "anthropic/claude-x")
    private val writer = Worker(name = "Writer", duty = "Update the docs", model = "openrouter/some-model")

    @Test
    fun idsAreSlugsWithThePrefix() {
        assertEquals("wf-tester", WorkflowAgents.idFor("Tester"))
        assertEquals("wf-unit-tests", WorkflowAgents.idFor("  Unit Tests!! "))
        assertEquals("wf-worker", WorkflowAgents.idFor(""))
        assertEquals("wf-worker", WorkflowAgents.idFor("\u00e9\u00e9"))
    }

    @Test
    fun aGoodWorkerHasNoProblems() {
        assertTrue(WorkflowAgents.problems(listOf(tester, writer)).isEmpty())
    }

    @Test
    fun badWorkersAreRefused() {
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(name = " "))).isEmpty())
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(name = "A:B"))).isEmpty())
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(duty = ""))).isEmpty())
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(model = "no-slash"))).isEmpty())
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(model = "a/b c"))).isEmpty())
        assertFalse(WorkflowAgents.problems(listOf(tester.copy(name = "x".repeat(41)))).isEmpty())
        // Two names that map to one id would overwrite each other.
        assertFalse(WorkflowAgents.problems(listOf(tester, tester.copy(name = "tester"))).isEmpty())
        val nine = (1..9).map { tester.copy(name = "w$it") }
        assertFalse(WorkflowAgents.problems(nine).isEmpty())
    }

    @Test
    fun additionsHaveAManagerAndOneSubagentPerWorker() {
        val agents = WorkflowAgents.additions(listOf(tester, writer))
        assertEquals("primary", agents.getJSONObject("workflow").getString("mode"))
        val t = agents.getJSONObject("wf-tester")
        assertEquals("subagent", t.getString("mode"))
        assertEquals("Tester: Write and run the unit tests", t.getString("description"))
        assertEquals("anthropic/claude-x", t.getString("model"))
        assertTrue(WorkflowAgents.additions(emptyList()).length() == 0)
    }

    @Test
    fun theManagerPromptNamesEveryWorkerAndItsDuty() {
        val prompt = WorkflowAgents.managerPrompt(listOf(tester, writer))
        assertTrue(prompt.contains("[wf-tester]: Write and run the unit tests"))
        assertTrue(prompt.contains("[wf-writer]: Update the docs"))
        assertTrue(prompt.contains("Rules:"))
    }

    @Test
    fun mergeKeepsHandWrittenAgentsAndDropsOldWorkers() {
        val existing = JSONObject()
            .put("reviewer", JSONObject().put("mode", "subagent").put("description", "Reviews code"))
            .put("wf-old", JSONObject().put("mode", "subagent").put("description", "Old: gone"))
            .put("workflow", JSONObject().put("mode", "primary"))
        val merged = WorkflowAgents.mergeAgents(existing, WorkflowAgents.additions(listOf(tester)))
        assertTrue(merged.has("reviewer"))
        assertFalse(merged.has("wf-old"))
        assertTrue(merged.has("wf-tester"))
        assertEquals("Manager: splits the work between your workers", merged.getJSONObject("workflow").getString("description"))
    }

    @Test
    fun theConfigReadsBackTheSameWorkers() {
        val config = WorkflowAgents.additions(listOf(writer, tester))
        val back = WorkflowAgents.fromConfig(config)
        assertEquals(listOf(tester, writer), back)
    }

    @Test
    fun handWrittenAgentsAreNeverShownAsWorkers() {
        val config = JSONObject()
            .put("wf-hand", JSONObject().put("mode", "primary").put("description", "Hand: not a worker"))
            .put("other", JSONObject().put("mode", "subagent").put("description", "Other: not a worker"))
            .put("wf-nodesc", JSONObject().put("mode", "subagent"))
        assertTrue(WorkflowAgents.fromConfig(config).isEmpty())
        assertTrue(WorkflowAgents.fromConfig(null).isEmpty())
    }

    @Test
    fun aModelWrittenAsAnObjectIsStillRead() {
        val config = JSONObject().put(
            "wf-tester",
            JSONObject()
                .put("mode", "subagent")
                .put("description", "Tester: Run tests")
                .put("model", JSONObject().put("providerID", "anthropic").put("modelID", "claude-x")),
        )
        assertEquals("anthropic/claude-x", WorkflowAgents.fromConfig(config).single().model)
    }
}
