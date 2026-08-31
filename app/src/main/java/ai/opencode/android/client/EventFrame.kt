package ai.opencode.android.client

import org.json.JSONObject

/**
 * Frame semantics of OpenCode's event stream, isolated so the exact rules are
 * unit-testable (they mirror the derivation the Phase 3/4 gate drivers verified
 * against the real server).
 *
 * A `/global/event` frame is `{directory, project, payload}`:
 *  * legacy bus events: `payload = {type, properties}`
 *  * durable (synced) events: `payload = {type: "sync", syncEvent: {type: "<name>.<version>", data}}`
 *    — the version suffix has to be stripped to get the logical event name.
 * An `/event` (instance-scoped) frame is already `{type, properties}`.
 */
object EventFrame {

    private val VERSION_SUFFIX = Regex("\\.\\d+$")

    fun deriveType(payload: JSONObject?): String {
        if (payload == null) return ""
        val syncType = payload.optJSONObject("syncEvent")?.opt("type") as? String
        val raw = syncType ?: (payload.opt("type") as? String) ?: ""
        return raw.replace(VERSION_SUFFIX, "")
    }

    fun properties(payload: JSONObject?): JSONObject {
        if (payload == null) return JSONObject()
        payload.optJSONObject("syncEvent")?.optJSONObject("data")?.let { return it }
        payload.optJSONObject("properties")?.let { return it }
        // Instance-scoped /event frames carry the properties inline.
        val out = JSONObject()
        for (k in payload.keys()) if (k != "type") out.put(k, payload.opt(k))
        return out
    }

    fun directoryOf(frame: JSONObject): String = frame.optString("directory")

    /** `payload.sessionID` for the handful of events that carry one directly. */
    fun sessionIDOf(type: String, properties: JSONObject): String {
        properties.optString("sessionID").takeIf { it.isNotEmpty() }?.let { return it }
        properties.optJSONObject("info")?.optString("sessionID")?.takeIf { it.isNotEmpty() }?.let { return it }
        properties.optJSONObject("part")?.optString("sessionID")?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** Events the UI treats as "this session is busy / became idle". */
    fun isSessionIdle(type: String): Boolean = type == "session.idle"
    fun isSessionStatus(type: String): Boolean = type == "session.status"
    fun isPartUpdated(type: String): Boolean = type == "message.part.updated"
    fun isPartRemoved(type: String): Boolean = type == "message.part.removed"
    fun isMessageUpdated(type: String): Boolean = type == "message.updated"
    fun isPermissionAsked(type: String): Boolean =
        type == "permission.asked" || type == "permission.v2.asked"
    fun isPermissionReplied(type: String): Boolean =
        type == "permission.replied" || type == "permission.v2.replied"
}
