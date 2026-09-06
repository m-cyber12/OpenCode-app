package ai.opencode.android.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 6 additions to [Transcript]: the streaming delta path,
 * the authoritative error channel, upstream retry state, questions, and the tool
 * and file payloads the chat rows render.
 *
 * The frame shapes are copied from upstream at the pinned commit
 * (`packages/core/src/v1/schema.ts`, `packages/opencode/src/server/server.ts` and
 * `session/processor.ts`), not invented, and the same frames reach this reducer
 * inside the app through `OpenCodeEventStream`. `ClientSemanticsTest` (Phase 5) is
 * frozen and still pins the older semantics; this file adds the new ones.
 */
class TranscriptPhase6Test {

    private val transcript = Transcript()

    private fun apply(type: String, propsJson: String) {
        transcript.apply(type, JSONObject(propsJson))
    }

    private fun view() = transcript.snapshot().session("s1")

    private fun messages() = view()?.messages ?: emptyList()

    private fun textPartFrame(id: String, text: String, messageID: String = "m1") =
        """{"part":{"id":"$id","sessionID":"s1","messageID":"$messageID","type":"text","text":${
            JSONObject.quote(text)
        },"state":{}}}"""

    private fun deltaFrame(partID: String, delta: String, field: String = "text", sessionID: String = "s1", messageID: String = "m1") =
        """{"sessionID":"$sessionID","messageID":"$messageID","partID":"$partID","field":"$field","delta":${JSONObject.quote(delta)}}"""

    // ---- streaming deltas ---------------------------------------------------

    @Test
    fun deltaAppendsOntoThePartItNames() {
        apply("message.part.updated", textPartFrame("p1", "Hel"))
        apply("message.part.delta", deltaFrame("p1", "lo, wor"))
        apply("message.part.delta", deltaFrame("p1", "ld"))
        val message = messages().single()
        assertEquals("Hello, world", message.parts.single().text)
        assertEquals("a delta must not create a second part", 1, message.parts.size)
    }

    @Test
    fun aCanonicalPartFrameAfterDeltasOverwritesRatherThanDoubles() {
        apply("message.part.updated", textPartFrame("p1", "Hel"))
        apply("message.part.delta", deltaFrame("p1", "lo"))
        apply("message.part.updated", textPartFrame("p1", "Hello world"))
        assertEquals("Hello world", messages().single().parts.single().text)
    }

    @Test
    fun severalPartsStreamIndependently() {
        apply("message.part.updated", textPartFrame("p1", "A"))
        apply("message.part.updated", textPartFrame("p2", "B"))
        apply("message.part.delta", deltaFrame("p2", "C"))
        val parts = messages().single().parts
        assertEquals(listOf("p1", "p2"), parts.map { it.id })
        assertEquals("A", parts[0].text)
        assertEquals("BC", parts[1].text)
    }

    @Test
    fun aDeltaForAPartThatHasNotArrivedYetIsDroppedNotSynthesised() {
        apply("message.part.delta", deltaFrame("nope", "x", messageID = "m9"))
        assertTrue("a stray delta must not invent a message or a session", transcript.snapshot().sessions.isEmpty())
    }

    @Test
    fun aDeltaNamingAnUnknownMessageIsIgnored() {
        apply("message.part.updated", textPartFrame("p1", "one"))
        apply("message.part.delta", deltaFrame("p1", "two", messageID = "m999"))
        assertEquals("one", messages().single().parts.single().text)
        assertEquals(1, messages().size)
    }

    @Test
    fun aDeltaOnAnotherFieldDoesNotTouchTheText() {
        apply("message.part.updated", textPartFrame("p1", "Hel"))
        apply("message.part.delta", deltaFrame("p1", "zzz", field = "input"))
        assertEquals("Hel", messages().single().parts.single().text)
    }

    @Test
    fun anEmptyDeltaChangesNothing() {
        apply("message.part.updated", textPartFrame("p1", "Hel"))
        apply("message.part.delta", deltaFrame("p1", ""))
        assertEquals("Hel", messages().single().parts.single().text)
    }

    @Test
    fun removedPartsDisappearFromTheMessage() {
        apply("message.part.updated", textPartFrame("p1", "keep"))
        apply("message.part.updated", textPartFrame("p2", "drop"))
        apply("message.part.removed", """{"sessionID":"s1","messageID":"m1","partID":"p2"}""")
        assertEquals(listOf("p1"), messages().single().parts.map { it.id })
    }

    // ---- the authoritative error channel ------------------------------------

    @Test
    fun sessionErrorBecomesTheTurnsTypedError() {
        apply(
            "session.error",
            """{"sessionID":"s1","error":{"name":"ProviderAuthError","providerID":"anthropic","message":"invalid api key"}}""",
        )
        val error = view()?.error
        assertNotNull(error)
        assertEquals("ProviderAuthError", error!!.name)
        assertEquals("invalid api key", error.message)
        assertEquals(AgentAvailability.PROVIDER_AUTH, error.kind)
    }

    @Test
    fun anAssistantMessageErrorIsMirroredOntoTheMessageAndTheSession() {
        apply(
            "message.updated",
            """{"info":{"id":"m1","sessionID":"s1","role":"assistant",
               "error":{"name":"APIError","message":"rate limited","statusCode":429,"isRetryable":true}}}""".trimIndent(),
        )
        val message = messages().single()
        assertEquals("APIError", message.error?.name)
        assertEquals(429, message.error?.statusCode)
        assertEquals(AgentAvailability.PROVIDER_UNREACHABLE, message.error?.kind)
        assertEquals("APIError", view()?.error?.name)
    }

    @Test
    fun startingANewTurnClearsThePreviousTurnsError() {
        apply("session.error", """{"sessionID":"s1","error":{"name":"ProviderAuthError","message":"invalid api key"}}""")
        assertNotNull(view()?.error)
        apply("session.status", """{"sessionID":"s1","status":{"type":"busy"}}""")
        assertNull("a new turn supersedes the failure it moved past", view()?.error)
        assertTrue(view()?.busy == true)
    }

    @Test
    fun anUnknownErrorShapeIsKeptRatherThanDiscarded() {
        apply("session.error", """{"sessionID":"s1","error":{"message":"something odd"}}""")
        val error = view()?.error
        assertEquals("", error?.name)
        assertEquals("something odd", error?.message)
        assertEquals(AgentAvailability.UNKNOWN, error?.kind)
    }

    @Test
    fun anEmptyErrorObjectIsNotATurnError() {
        apply("session.error", """{"sessionID":"s1","error":{}}""")
        assertNull(view()?.error)
    }

    // ---- retry / backoff ----------------------------------------------------

    @Test
    fun retryStatusCarriesTheAttemptNumber() {
        apply(
            "session.status",
            """{"sessionID":"s1","status":{"type":"retry","attempt":2,"message":"Retrying in 3s","next":1700000000000}}""",
        )
        val retry = view()?.retry
        assertNotNull(retry)
        assertEquals(2, retry!!.attempt)
        assertEquals("Retrying in 3s", retry.message)
        assertEquals(1700000000000L, retry.nextMs)
        assertTrue("a retrying session is busy", view()?.busy == true)
    }

    @Test
    fun aLaterBusyStatusClearsTheRetryState() {
        apply("session.status", """{"sessionID":"s1","status":{"type":"retry","attempt":1}}""")
        apply("session.status", """{"sessionID":"s1","status":{"type":"busy"}}""")
        assertNull(view()?.retry)
        assertTrue(view()?.busy == true)
    }

    @Test
    fun idleClearsRetryAndBusy() {
        apply("session.status", """{"sessionID":"s1","status":{"type":"retry","attempt":1}}""")
        apply("session.idle", """{"sessionID":"s1"}""")
        assertNull(view()?.retry)
        assertFalse(view()?.busy == true)
    }

    // ---- questions ----------------------------------------------------------

    @Test
    fun questionsAreTrackedAndClearedWhenRepliedTo() {
        apply(
            "question.asked",
            """{"id":"que_1","sessionID":"s1","questions":[
               {"header":"Deploy","question":"Which target?","multiple":false,"custom":true,
                "options":[{"label":"staging","description":"the staging box"},{"label":"prod"}]}]}""".trimIndent(),
        )
        val ask = view()?.questions?.single()
        assertEquals("que_1", ask?.id)
        assertEquals(1, ask?.items?.size)
        assertEquals("Deploy", ask?.items?.get(0)?.header)
        assertEquals("Which target?", ask?.items?.get(0)?.question)
        assertEquals("staging", ask?.items?.get(0)?.options?.get(0)?.label)
        assertEquals("the staging box", ask?.items?.get(0)?.options?.get(0)?.description)
        assertEquals(false, ask?.items?.get(0)?.multiple)
        assertEquals(1, transcript.snapshot().questionTotal())
        assertNotNull(transcript.question("que_1"))

        apply("question.replied", """{"requestID":"que_1"}""")
        assertTrue((view()?.questions ?: emptyList()).isEmpty())
        assertEquals(0, transcript.snapshot().questionTotal())
        assertNull(transcript.question("que_1"))
    }

    @Test
    fun aRejectedQuestionIsAlsoRemoved() {
        apply("question.asked", """{"id":"que_2","sessionID":"s1","questions":[{"question":"?","options":[]}]}""")
        assertEquals(1, transcript.snapshot().questionTotal())
        apply("question.rejected", """{"requestID":"que_2"}""")
        assertEquals(0, transcript.snapshot().questionTotal())
    }

    @Test
    fun anIdThatIsNotAQuestionIsNotInvented() {
        apply("question.asked", """{"id":"not_a_question","sessionID":"s1","questions":[]}""")
        assertEquals(0, transcript.snapshot().questionTotal())
    }

    @Test
    fun customDefaultsToTrueWhenUpstreamOmitsIt() {
        apply("question.asked", """{"id":"que_3","sessionID":"s1","questions":[{"question":"?","options":[]}]}""")
        assertEquals(true, view()?.questions?.single()?.items?.single()?.custom)
    }

    // ---- permissions --------------------------------------------------------

    @Test
    fun aPermissionAskKeepsTheAlwaysScopeAndItsToolCall() {
        apply(
            "permission.asked",
            """{"id":"per_1","sessionID":"s1","permission":"bash","patterns":["git push*"],
               "always":["git push"],"metadata":{"command":"git push origin main"},
               "tool":{"messageID":"m1","callID":"call_9"}}""".trimIndent(),
        )
        val prompt = view()?.pending?.single()
        assertEquals("per_1", prompt?.id)
        assertEquals("bash", prompt?.permission)
        assertEquals(listOf("git push*"), prompt?.patterns)
        assertEquals(listOf("git push"), prompt?.always)
        assertEquals("call_9", prompt?.toolCallID)
        assertTrue("the metadata is kept verbatim for the card body", prompt?.metadata?.contains("git push origin main") == true)
        assertEquals(1, transcript.snapshot().pendingTotal())
        assertNotNull(transcript.prompt("per_1"))
    }

    @Test
    fun aPermissionReplyRemovesThePrompt() {
        apply("permission.asked", """{"id":"per_2","sessionID":"s1","permission":"edit","metadata":{}}""")
        apply("permission.replied", """{"requestID":"per_2","response":"once"}""")
        assertTrue((view()?.pending ?: emptyList()).isEmpty())
        assertEquals(0, transcript.snapshot().pendingTotal())
    }

    @Test
    fun theServersOwnPendingListReplacesWhatTheStreamGuessed() {
        apply("permission.asked", """{"id":"per_3","sessionID":"s1","permission":"bash","metadata":{}}""")
        transcript.replacePrompts(
            listOf(
                Transcript.Prompt(
                    id = "per_4",
                    sessionID = "s1",
                    permission = "edit",
                    patterns = emptyList(),
                    metadata = "{}",
                ),
            ),
        )
        val pending = view()?.pending ?: emptyList()
        assertEquals(listOf("per_4"), pending.map { it.id })
    }

    // ---- part payloads the UI renders ---------------------------------------

    @Test
    fun aToolPartKeepsItsStateMetadataAndTiming() {
        apply(
            "message.part.updated",
            """{"part":{"id":"t1","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash","callID":"call_1",
               "state":{"status":"completed","input":{"command":"ls -la"},"title":"ls -la",
                        "metadata":{"output":"total 8","exit":0},"time":{"start":1000,"end":2000}}}}""".trimIndent(),
        )
        val part = messages().single().parts.single()
        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertEquals(ToolKind.SHELL, ToolKinds.of(part.tool))
        assertEquals("ls -la", part.title)
        assertEquals("completed", part.status)
        assertEquals("call_1", part.callID)
        assertEquals(1000L, part.timeStart)
        assertEquals(2000L, part.timeEnd)
        assertTrue(part.input.contains("ls -la"))
        assertEquals("total 8", part.output)
        val meta = ToolMetaParser.parse(part.metadata)
        assertEquals("total 8", meta.output)
        assertEquals(0, meta.exit)
    }

    @Test
    fun aToolPartInErrorStateKeepsTheErrorText() {
        apply(
            "message.part.updated",
            """{"part":{"id":"t2","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash","callID":"c2",
               "state":{"status":"error","error":"command not found: nope"}}}""".trimIndent(),
        )
        val part = messages().single().parts.single()
        assertEquals("error", part.status)
        assertEquals("command not found: nope", part.error)
    }

    @Test
    fun aToolPartMovesFromRunningToCompletedInPlace() {
        apply(
            "message.part.updated",
            """{"part":{"id":"t3","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash",
               "state":{"status":"running","input":{"command":"sleep 1"}}}}""".trimIndent(),
        )
        assertEquals("running", messages().single().parts.single().status)
        apply(
            "message.part.updated",
            """{"part":{"id":"t3","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash",
               "state":{"status":"completed","output":"done","metadata":{"exit":0}}}}""".trimIndent(),
        )
        val parts = messages().single().parts
        assertEquals("a state change must not add a second card", 1, parts.size)
        assertEquals("completed", parts.single().status)
        assertEquals("done", parts.single().output)
    }

    @Test
    fun aFilePartKeepsMimeNameAndUrl() {
        apply(
            "message.part.updated",
            """{"part":{"id":"f1","sessionID":"s1","messageID":"m1","type":"file",
               "mime":"image/png","filename":"shot.png","url":"file:///data/user/0/pkg/files/attachments/1-shot.png",
               "state":{}}}""".trimIndent(),
        )
        val part = messages().single().parts.single()
        assertEquals("file", part.type)
        assertEquals("image/png", part.mime)
        assertEquals("shot.png", part.filename)
        assertTrue(part.url.startsWith("file:///"))
        assertFalse(part.synthetic)
    }

    @Test
    fun bookkeepingPartsAreMirroredWholeSoTheUiCanDecideToHideThem() {
        apply(
            "message.part.updated",
            """{"part":{"id":"b1","sessionID":"s1","messageID":"m1","type":"patch","snapshot":"snap_1","hashes":{}}}""",
        )
        apply(
            "message.part.updated",
            """{"part":{"id":"b2","sessionID":"s1","messageID":"m1","type":"step-finish","state":{}}}""",
        )
        val types = messages().single().parts.map { it.type }
        assertEquals(listOf("patch", "step-finish"), types)
    }

    // ---- history load -------------------------------------------------------

    @Test
    fun loadMessagesDerivesTheLastErrorFromHistory() {
        val withError = OpenCodeApi.MessageInfo(
            id = "m1",
            role = "assistant",
            parts = emptyList(),
            info = JSONObject("""{"id":"m1","role":"assistant","error":{"name":"ProviderAuthError","message":"bad key"}}"""),
        )
        transcript.loadMessages("s1", listOf(withError))
        assertEquals("ProviderAuthError", view()?.error?.name)
        assertEquals(AgentAvailability.PROVIDER_AUTH, view()?.error?.kind)

        val clean = OpenCodeApi.MessageInfo(id = "m2", role = "assistant", parts = emptyList(), info = null)
        transcript.loadMessages("s1", listOf(clean))
        assertNull("a later clean history must clear the banner", view()?.error)
    }

    @Test
    fun loadedPartsBecomeRenderablePartsInServerOrder() {
        val message = OpenCodeApi.MessageInfo(
            id = "m1",
            role = "user",
            parts = listOf(
                JSONObject("""{"id":"p1","type":"text","text":"hello there"}"""),
                JSONObject("""{"id":"p2","type":"file","mime":"text/plain","filename":"a.txt","url":"file:///tmp/a.txt"}"""),
            ),
            info = JSONObject("""{"id":"m1","role":"user","time":{"created":10,"completed":11}}"""),
        )
        transcript.loadMessages("s1", listOf(message))
        val loaded = messages().single()
        assertEquals("user", loaded.role)
        assertTrue("a message with time.completed is not still streaming", loaded.completed)
        assertEquals(listOf("text", "file"), loaded.parts.map { it.type })
        assertEquals("hello there", loaded.parts[0].text)
        assertEquals("a.txt", loaded.parts[1].filename)
    }

    @Test
    fun loadMessagesReplacesTheOlderTranscriptRatherThanAppending() {
        apply("message.part.updated", textPartFrame("p1", "stale"))
        transcript.loadMessages(
            "s1",
            listOf(OpenCodeApi.MessageInfo(id = "m2", role = "user", parts = emptyList(), info = null)),
        )
        assertEquals(listOf("m2"), messages().map { it.id })
    }

    // ---- session-level view -------------------------------------------------

    @Test
    fun busySessionsLooksAcrossSessions() {
        apply("session.status", """{"sessionID":"s9","status":{"type":"busy"}}""")
        assertEquals(listOf("s9"), transcript.snapshot().busySessions())
        apply("session.idle", """{"sessionID":"s9"}""")
        assertTrue(transcript.snapshot().busySessions().isEmpty())
    }

    @Test
    fun deletingASessionTakesItsMessagesPromptsAndErrorsWithIt() {
        apply("message.part.updated", textPartFrame("p1", "x"))
        apply("permission.asked", """{"id":"per_5","sessionID":"s1","permission":"bash","metadata":{}}""")
        apply("session.error", """{"sessionID":"s1","error":{"name":"APIError","message":"boom","statusCode":500}}""")
        apply("session.deleted", """{"sessionID":"s1"}""")
        assertNull(transcript.snapshot().session("s1"))
        assertEquals(0, transcript.snapshot().pendingTotal())
    }
}
