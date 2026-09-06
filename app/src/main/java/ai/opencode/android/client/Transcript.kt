package ai.opencode.android.client

import org.json.JSONObject

/**
 * Pure reducer for OpenCode's transcript events.
 *
 * The Android client never reimplements the agent: the server owns messages,
 * parts, tool state and permissions, and the client just mirrors what the
 * stream says. This class is the mirror — no Android, no coroutines, so the
 * mapping is covered by JVM unit tests with real captured frames.
 */
class Transcript {

    data class Part(
        val id: String,
        val messageID: String,
        val sessionID: String,
        val type: String,
        val text: String,
        val tool: String,
        val callID: String,
        val status: String,
        val input: String,
        val output: String,
        val title: String,
    )

    data class Message(
        val id: String,
        val sessionID: String,
        val role: String,
        val parts: List<Part>,
        val completed: Boolean,
    )

    data class Prompt(val id: String, val sessionID: String, val permission: String, val patterns: List<String>, val metadata: String)

    /** Ordered message ids per session (insertion order = server order). */
    private val order = LinkedHashMap<String, MutableList<String>>()
    private val messages = HashMap<String, Message>()
    private val sessionOrder = mutableListOf<String>()

    /** Sessions with an in-flight turn, per `session.status`/`session.idle`. */
    private val busy = HashMap<String, Boolean>()
    /** Pending permission asks by session (server also exposes GET /permission). */
    private val prompts = LinkedHashMap<String, Prompt>()

    /** True when the last apply() changed something the UI renders. */
    var dirty: Boolean = false
        private set

    fun snapshot(): Snapshot = Snapshot(
        sessions = sessionOrder.map { sid ->
            SessionView(
                sessionID = sid,
                busy = busy[sid] == true,
                messages = order[sid]?.mapNotNull { messages[it] } ?: emptyList(),
                pending = prompts.values.filter { it.sessionID == sid },
            )
        },
    )

    data class SessionView(
        val sessionID: String,
        val busy: Boolean,
        val messages: List<Message>,
        val pending: List<Prompt>,
    )

    data class Snapshot(val sessions: List<SessionView>) {
        fun pendingTotal(): Int = sessions.sumOf { it.pending.size }
        fun busySessions(): List<String> = sessions.filter { it.busy }.map { it.sessionID }
    }

    /** Replace everything with a server snapshot (GET /session/:id/message). */
    fun loadMessages(sessionID: String, list: List<OpenCodeApi.MessageInfo>) {
        touch(sessionID)
        val ids = ArrayList<String>()
        for (m in list) {
            if (m.id.isEmpty()) continue
            val parts = ArrayList<Part>(m.parts.size)
            for (raw in m.parts) parts.add(partOf(sessionID, m.id, raw))
            messages[m.id] = Message(id = m.id, sessionID = sessionID, role = m.role, parts = parts, completed = false)
            ids.add(m.id)
        }
        order[sessionID] = ids
        dirty = true
    }

    /**
     * Apply one event frame. Unknown event types are ignored on purpose (the
     * server may add events; the client must not crash on them).
     */
    fun apply(type: String, props: JSONObject) {
        when {
            type == "message.part.updated" -> onPartUpdated(props)
            type == "message.part.removed" -> onPartRemoved(props)
            type == "message.updated" -> onMessageUpdated(props)
            type == "session.updated" -> onSessionUpdated(props)
            type == "session.deleted" -> onSessionDeleted(props)
            EventFrame.isSessionStatus(type) -> onSessionStatus(props)
            EventFrame.isSessionIdle(type) -> { setBusy(props.optString("sessionID"), false) }
            EventFrame.isPermissionAsked(type) -> onAsked(props)
            EventFrame.isPermissionReplied(type) -> onReplied(props)
            else -> Unit
        }
    }

    // ---- individual event handlers ----------------------------------------

    private fun onSessionStatus(props: JSONObject) {
        val sid = props.optString("sessionID")
        val status = props.optJSONObject("status")?.optString("type") ?: ""
        if (sid.isNotEmpty()) setBusy(sid, status == "busy" || status == "retry")
    }

    private fun setBusy(sid: String, value: Boolean) {
        if (sid.isEmpty()) return
        if (busy[sid] != value) {
            busy[sid] = value
            // The session row has to exist for the spinner to render on it: a
            // session.status/idle frame can legitimately arrive before any part
            // frame (e.g. right after an app restart mid-turn), and previously the
            // busy flag had no session to attach to and was invisible.
            touch(sid)
            dirty = true
        }
    }

    private fun onPartUpdated(props: JSONObject) {
        val part = props.optJSONObject("part") ?: return
        val sid = part.optString("sessionID").ifEmpty { props.optString("sessionID") }
        // Upstream's Part carries its own messageID; the legacy bus frame also puts
        // it on the envelope. Accept either, same fallback the sessionID uses, so a
        // frame that names the message nowhere cannot create a phantom message.
        val mid = part.optString("messageID").ifEmpty { props.optString("messageID") }
        if (sid.isEmpty() || mid.isEmpty()) return
        touch(sid)
        val p = partOf(sid, mid, part)
        val existing = messages[mid]
        val parts = ArrayList(existing?.parts ?: emptyList())
        val idx = parts.indexOfFirst { it.id == p.id }
        if (idx >= 0) parts[idx] = p else parts.add(p)
        // A tool part moving to running/completed means the turn is still live.
        messages[mid] = (existing ?: Message(mid, sid, part.optString("role"), emptyList(), false))
            .copy(parts = parts)
        idsFor(sid).addIfAbsent(mid)
        dirty = true
    }

    private fun onPartRemoved(props: JSONObject) {
        val sid = props.optString("sessionID")
        val mid = props.optString("messageID")
        val pid = props.optString("partID")
        val m = messages[mid] ?: return
        messages[mid] = m.copy(parts = m.parts.filterNot { it.id == pid })
        if (sid.isEmpty() || order[sid]?.contains(mid) == true) dirty = true
    }

    private fun onMessageUpdated(props: JSONObject) {
        val info = props.optJSONObject("info") ?: props
        val mid = info.optString("id")
        val sid = info.optString("sessionID")
        if (mid.isEmpty() || sid.isEmpty()) return
        touch(sid)
        val existing = messages[mid]
        messages[mid] = Message(
            id = mid,
            sessionID = sid,
            role = info.optString("role").ifEmpty { existing?.role ?: "" },
            parts = existing?.parts ?: emptyList(),
            completed = info.optJSONObject("time")?.has("completed") == true,
        )
        idsFor(sid).addIfAbsent(mid)
        dirty = true
    }

    private fun onSessionUpdated(props: JSONObject) {
        val info = props.optJSONObject("info") ?: props
        val sid = info.optString("id")
        if (sid.isNotEmpty()) touch(sid)
    }

    private fun onSessionDeleted(props: JSONObject) {
        val sid = props.optString("sessionID")
        if (sid.isEmpty()) return
        order[sid]?.forEach { messages.remove(it) }
        order.remove(sid)
        sessionOrder.remove(sid)
        busy.remove(sid)
        prompts.keys.filter { prompts[it]?.sessionID == sid }.forEach { prompts.remove(it) }
        dirty = true
    }

    private fun onAsked(props: JSONObject) {
        val id = firstString(props, "id", "requestID")
        if (id.isEmpty() || !id.startsWith("per_")) return
        val patterns = ArrayList<String>()
        props.optJSONArray("patterns")?.let { for (i in 0 until it.length()) patterns.add(it.optString(i)) }
        val sid = props.optString("sessionID")
        prompts[id] = Prompt(
            id = id,
            sessionID = sid,
            permission = props.optString("permission"),
            patterns = patterns,
            metadata = props.optJSONObject("metadata")?.toString() ?: "",
        )
        // Same reason as setBusy: an ask must be visible under its session even when
        // no transcript frame for that session has arrived yet, otherwise the
        // Approvals tab could never show a permission the server is genuinely waiting
        // on (and blocking a turn on).
        if (sid.isNotEmpty()) touch(sid)
        dirty = true
    }

    private fun onReplied(props: JSONObject) {
        val id = firstString(props, "requestID", "id")
        if (id.isEmpty()) return
        if (prompts.remove(id) != null) dirty = true
    }

    /** Merge the server's authoritative pending list (GET /permission). */
    fun replacePrompts(serverPrompts: List<Prompt>) {
        prompts.clear()
        serverPrompts.forEach { prompts[it.id] = it }
        dirty = true
    }

    fun prompt(id: String): Prompt? = prompts[id]

    // ---- helpers -----------------------------------------------------------

    private fun partOf(sessionID: String, messageID: String, part: JSONObject): Part {
        val state = part.optJSONObject("state")
        return Part(
            id = part.optString("id"),
            messageID = messageID,
            sessionID = sessionID,
            type = part.optString("type"),
            text = part.optString("text"),
            tool = part.optString("tool"),
            callID = part.optString("callID"),
            status = state?.optString("status") ?: "",
            input = state?.opt("input")?.toString() ?: "",
            output = state?.opt("output")?.toString()
                ?: state?.optJSONObject("metadata")?.optString("output") ?: "",
            title = state?.optString("title") ?: part.optString("title"),
        )
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) (o.opt(k) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun touch(sid: String) {
        if (sid.isNotEmpty() && sid !in sessionOrder) sessionOrder.add(sid)
    }

    private fun idsFor(sid: String): MutableList<String> = order.getOrPut(sid) { mutableListOf() }

    private fun MutableList<String>.addIfAbsent(v: String) {
        if (!contains(v)) add(v)
    }
}
