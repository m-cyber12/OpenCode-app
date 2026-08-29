package ai.opencode.android.runtime

import org.json.JSONObject
import java.io.File

/**
 * The payload manifest: generated at packaging time by
 * `phase4/scripts/10-build-payload.sh`, shipped inside the assets payload
 * (runtime-manifest.json) AND as a top-level asset for quick access.
 *
 * It lists every extracted file with its sha256 and length, plus versions.
 * The extractor validates every entry after extraction (corruption detection)
 * and the supervisor re-extracts on mismatch (recovery).
 */
data class ManifestEntry(val path: String, val sha256: String, val size: Long) {
    companion object {
        fun fromJson(o: JSONObject): ManifestEntry =
            ManifestEntry(
                path = o.getString("path"),
                sha256 = o.getString("sha256"),
                size = o.getLong("size"),
            )
    }
}

data class RuntimeManifest(
    val payloadVersion: Int,
    val opencodeCommit: String,
    val opencodeVersion: String,
    val bunVersion: String,
    val gitVersion: String,
    val rgVersion: String,
    val payloadSha256: String,
    val entries: List<ManifestEntry>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("payloadVersion", payloadVersion)
        put("opencodeCommit", opencodeCommit)
        put("opencodeVersion", opencodeVersion)
        put("bunVersion", bunVersion)
        put("gitVersion", gitVersion)
        put("rgVersion", rgVersion)
        put("payloadSha256", payloadSha256)
        put("files", JSONObject().apply {
            entries.forEach { put(it.path, JSONObject().put("sha256", it.sha256).put("size", it.size)) }
        })
    }

    companion object {
        fun fromJson(text: String): RuntimeManifest {
            val o = JSONObject(text)
            val files = o.getJSONObject("files")
            val entries = files.keys().asSequence().map { k ->
                val e = files.getJSONObject(k)
                ManifestEntry(k, e.getString("sha256"), e.getLong("size"))
            }.toList()
            return RuntimeManifest(
                payloadVersion = o.getInt("payloadVersion"),
                opencodeCommit = o.getString("opencodeCommit"),
                opencodeVersion = o.getString("opencodeVersion"),
                bunVersion = o.getString("bunVersion"),
                gitVersion = o.getString("gitVersion"),
                rgVersion = o.getString("rgVersion"),
                payloadSha256 = o.optString("payloadSha256", ""),
                entries = entries,
            )
        }

        fun fromFile(f: File): RuntimeManifest = fromJson(f.readText())
    }
}
