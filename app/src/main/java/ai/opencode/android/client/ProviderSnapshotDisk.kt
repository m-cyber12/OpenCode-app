package ai.opencode.android.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * v9.41 (owner item 7): the provider snapshot survives restarts on disk.
 *
 * The catalog (which providers exist, which models each lists, which are free)
 * changes on the scale of releases, not on the scale of app launches - yet
 * every cold start began from `providers = null` and showed an empty/loading
 * model surface until the local server answered. The repository already keeps
 * an in-process cache (v9.40); this is the same snapshot written through to
 * prefs, so a fresh process starts with yesterday's catalog instantly and
 * silently swaps in the server's answer when it arrives (stale-while-revalidate
 * - the server stays the truth, the disk is only the first frame).
 */
interface ProviderSnapshotDisk {
    fun load(): OpenCodeApi.ProviderSnapshot?
    fun save(snapshot: OpenCodeApi.ProviderSnapshot)
}

/** JSON both ways for the parts the UI reads. Unknown shapes load as null. */
object ProviderSnapshotCodec {

    fun encode(p: OpenCodeApi.ProviderSnapshot): String {
        val o = JSONObject()
        o.put("allIds", JSONArray(p.allIds))
        o.put("connected", JSONArray(p.connected))
        o.put("defaultModel", JSONObject(p.defaultModel))
        val entries = JSONArray()
        for (e in p.entries) {
            val eo = JSONObject()
            eo.put("id", e.id)
            eo.put("name", e.name)
            val models = JSONArray()
            for (m in e.models) {
                val mo = JSONObject()
                mo.put("id", m.id)
                mo.put("name", m.name)
                mo.put("status", m.status)
                mo.put("variants", JSONArray(m.variants))
                models.put(mo)
            }
            eo.put("models", models)
            entries.put(eo)
        }
        o.put("entries", entries)
        return o.toString()
    }

    fun decode(raw: String): OpenCodeApi.ProviderSnapshot? = runCatching {
        val o = JSONObject(raw)
        val allIds = ArrayList<String>()
        o.optJSONArray("allIds")?.let { a -> for (i in 0 until a.length()) allIds.add(a.getString(i)) }
        val connected = ArrayList<String>()
        o.optJSONArray("connected")?.let { a -> for (i in 0 until a.length()) connected.add(a.getString(i)) }
        val defaults = LinkedHashMap<String, String>()
        o.optJSONObject("defaultModel")?.let { d -> for (k in d.keys()) defaults[k] = d.getString(k) }
        val entries = ArrayList<OpenCodeApi.ProviderEntry>()
        o.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val eo = arr.optJSONObject(i) ?: continue
                val models = ArrayList<OpenCodeApi.ModelEntry>()
                eo.optJSONArray("models")?.let { ms ->
                    for (j in 0 until ms.length()) {
                        val mo = ms.optJSONObject(j) ?: continue
                        val variants = ArrayList<String>()
                        mo.optJSONArray("variants")?.let { v ->
                            for (k in 0 until v.length()) variants.add(v.getString(k))
                        }
                        models.add(
                            OpenCodeApi.ModelEntry(
                                id = mo.getString("id"),
                                name = mo.getString("name"),
                                status = mo.optString("status", ""),
                                variants = variants,
                            ),
                        )
                    }
                }
                entries.add(
                    OpenCodeApi.ProviderEntry(
                        id = eo.getString("id"),
                        name = eo.getString("name"),
                        models = models,
                    ),
                )
            }
        }
        if (entries.isEmpty() && connected.isEmpty()) null
        else OpenCodeApi.ProviderSnapshot(allIds, connected, defaults, entries)
    }.getOrNull()
}

/** The SharedPreferences body; one instance per process via [get]. */
class PrefsProviderSnapshotDisk(private val prefs: SharedPreferences) : ProviderSnapshotDisk {

    override fun load(): OpenCodeApi.ProviderSnapshot? =
        ProviderSnapshotCodec.decode(prefs.getString(KEY, "").orEmpty())

    override fun save(snapshot: OpenCodeApi.ProviderSnapshot) {
        runCatching {
            prefs.edit().putString(KEY, ProviderSnapshotCodec.encode(snapshot)).apply()
        }
    }

    companion object {
        private const val KEY = "opencode_providers_snapshot_v1"
        private const val PREFS_FILE = "opencode"

        @Volatile
        private var instance: PrefsProviderSnapshotDisk? = null

        fun get(context: Context): PrefsProviderSnapshotDisk =
            instance ?: synchronized(this) {
                instance ?: PrefsProviderSnapshotDisk(
                    context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
