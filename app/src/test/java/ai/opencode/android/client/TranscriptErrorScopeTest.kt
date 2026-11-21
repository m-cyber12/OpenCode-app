package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-11-20 (owner bug): a failed prompt made every later prompt show the same error, and the
 * Send button never turned into Stop. A session's error must belong to the turn that produced
 * it: a new user message ends that turn's failure, live or reloaded from history.
 */
class TranscriptErrorScopeTest {

    private val transcript = Transcript()

    private fun apply(type: String, propsJson: String) {
        transcript.apply(type, JSONObject(propsJson))
    }

    private fun view() = transcript.snapshot().session("s1")

    private fun user(id: String) = OpenCodeApi.MessageInfo(
        id = id,
        role = "user",
        parts = emptyList(),
        info = JSONObject("""{"id":"$id","sessionID":"s1","role":"user"}"""),
    )

    private fun assistant(id: String, error: String? = null) = OpenCodeApi.MessageInfo(
        id = id,
        role = "assistant",
        parts = emptyList(),
        info = JSONObject(
            if (error == null) {
                """{"id":"$id","sessionID":"s1","role":"assistant","time":{"completed":2}}"""
            } else {
                """{"id":"$id","sessionID":"s1","role":"assistant","error":{"name":"ProviderAuthError","message":"$error"}}"""
            },
        ),
    )

    @Test
    fun aLiveErrorEndsWhenTheNextPromptArrives() {
        apply("session.error", """{"sessionID":"s1","error":{"name":"ProviderAuthError","message":"invalid api key"}}""")
        assertNotNull(view()?.error)
        apply("message.updated", """{"info":{"id":"u2","sessionID":"s1","role":"user"}}""")
        assertNull("the next prompt is a new turn", view()?.error)
    }

    @Test
    fun aFailedTurnIsNoLongerBusy() {
        // The server sends no idle frame after an error: the error alone must end the turn,
        // or Send stays replaced by Pause and the user is stuck.
        apply("session.status", """{"sessionID":"s1","status":{"type":"busy"}}""")
        assertEquals(true, view()?.busy)
        apply("session.error", """{"sessionID":"s1","error":{"name":"ProviderAuthError","message":"invalid api key"}}""")
        assertEquals(false, view()?.busy)
        assertNotNull(view()?.error)
    }

    @Test
    fun aReloadedErrorDoesNotSurviveALaterSuccessfulTurn() {
        transcript.loadMessages(
            "s1",
            listOf(user("u1"), assistant("a1", error = "invalid api key"), user("u2"), assistant("a2")),
        )
        assertNull("the failure belonged to the first turn only", view()?.error)
    }

    @Test
    fun aReloadedErrorOfTheCurrentTurnIsStillShown() {
        transcript.loadMessages(
            "s1",
            listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2", error = "invalid api key")),
        )
        assertEquals("ProviderAuthError", view()?.error?.name)
    }

    @Test
    fun aNewPromptAfterAReloadedFailureClearsIt() {
        transcript.loadMessages("s1", listOf(user("u1"), assistant("a1", error = "invalid api key")))
        assertNotNull(view()?.error)
        apply("message.updated", """{"info":{"id":"u2","sessionID":"s1","role":"user"}}""")
        assertNull(view()?.error)
    }

    @Test
    fun anExistingUserMessageUpdateDoesNotClearTheCurrentError() {
        transcript.loadMessages("s1", listOf(user("u1"), assistant("a1", error = "invalid api key")))
        apply("message.updated", """{"info":{"id":"u1","sessionID":"s1","role":"user"}}""")
        assertNotNull("only a new user message starts a new turn", view()?.error)
    }
}
