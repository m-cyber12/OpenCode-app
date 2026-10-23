package ai.opencode.android.client

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * v9.42 (owner: "it takes a while for the previous chat to appear when the user
 * opens the app or switches sessions - initially the user sees an empty chat
 * screen"): the last loaded transcript, on disk, per project.
 *
 * A cold app open used to show an empty conversation until `GET /session/:id/
 * message` answered (hundreds of milliseconds to seconds on a phone, and the
 * payload can be large). Now the chat paints the conversation the user was
 * reading the instant the screen exists, and the server's answer replaces it
 * a moment later - stale-while-revalidate, the same trick the provider catalog
 * already uses ([ProviderSnapshotDisk]).
 *
 * Rules that keep it honest:
 *  - consulted ONLY when the in-memory transcript has nothing for the session,
 *    so a live turn is never briefly replaced by an older copy;
 *  - overwritten on every successful load and after every completed turn;
 *  - a missing, corrupt or truncated file is simply "no cache" - the app falls
 *    back to the network exactly as before, and never shows an error for it.
 *
 * It lives in cacheDir (a cache, disposable by the system at any time), is
 * keyed by project directory, and holds NO secret: message text is the user's
 * own conversation, already readable in the app's own storage.
 */
class TranscriptDisk(
    private val key: String,
    private val dir: File,
) {

    /** Guard against caching something absurd (a monster transcript). */
    private val maxChars = 4_000_000

    private fun file(): File? {
        val safe = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        if (safe.isEmpty()) return null
        val root = File(dir, "transcript-cache")
        return File(root, "$safe.json")
    }

    fun save(sessionID: String, messages: List<OpenCodeApi.MessageInfo>) {
        if (sessionID.isEmpty() || messages.isEmpty()) return
        runCatching {
            val f = file() ?: return
            val arr = JSONArray()
            for (m in messages) {
                val parts = JSONArray()
                for (p in m.parts) parts.put(p)
                arr.put(JSONObject().put("info", m.info ?: JSONObject()).put("parts", parts))
            }
            val body = JSONObject().put("sessionID", sessionID).put("messages", arr).toString()
            if (body.length > maxChars) return
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(body)
            // Atomic-ish swap: a killed write leaves the previous copy, never a
            // half file that would parse as "empty conversation".
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    fun load(sessionID: String): List<OpenCodeApi.MessageInfo>? {
        if (sessionID.isEmpty()) return null
        return runCatching {
            val f = file() ?: return null
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            if (o.optString("sessionID") != sessionID) return null
            val arr = o.optJSONArray("messages") ?: return null
            val out = ArrayList<OpenCodeApi.MessageInfo>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(OpenCodeApi.MessageInfo.from(arr.getJSONObject(i)))
            }
            if (out.isEmpty()) null else out
        }.getOrNull()
    }

    companion object {
        /** One instance per project directory, in the app's cache. */
        fun get(context: android.content.Context, workspaceDir: String?): TranscriptDisk =
            TranscriptDisk(
                key = (workspaceDir ?: "default").hashCode().toString(),
                dir = File(context.cacheDir, "opencode"),
            )
    }
}
