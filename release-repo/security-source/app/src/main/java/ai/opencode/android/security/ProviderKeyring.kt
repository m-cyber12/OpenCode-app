package ai.opencode.android.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * v6.1 (owner decision, 2026-09-24): several API keys PER PROVIDER, with one
 * "active" key - because the OpenCode server itself can hold exactly ONE
 * credential per provider id (`PUT /auth/:providerID` replaces; upstream
 * `auth.json` is a map keyed by provider). Multiple keys therefore live on the
 * app side: this keyring.
 *
 * What lives where - and why:
 *
 *  * **Key values** go into the Keystore-backed [SecretStore] (through [Vault],
 *    so this class stays JVM-testable), one blob per key under
 *    `provider:<id>:<slot>`. The ACTIVE key is additionally mirrored at the
 *    legacy name `provider:<id>` - the name every pre-keyring code path
 *    (listing, revocation, re-provisioning on restart) already reads - so an
 *    install that upgrades keeps working without a migration step anywhere else.
 *  * **Metadata** (labels, last-4 display, which slot is active) is a small JSON
 *    document the caller persists; it contains no key material.
 *
 * The active key is pushed to the server by the CALLER ([provisionProvider]/
 * `activateProviderKey` in the repository): this class never talks to the
 * network, which is what makes its behaviour provable in plain JVM tests.
 *
 * Adoption: a provider that has a legacy single key but no keyring metadata is
 * adopted on first read as "Key 1" (active) - the upgrade path for every
 * existing install, exercised as a unit test rather than assumed.
 */
class ProviderKeyring(
    private val loadMeta: () -> String,
    private val saveMeta: (String) -> Unit,
    private val vault: Vault,
) {

    /** The minimal surface of [SecretStore] this class needs (JVM-fakeable). */
    interface Vault {
        fun put(name: String, value: String)
        fun get(name: String): String?
        fun delete(name: String): Boolean
        fun contains(name: String): Boolean
    }

    /** One saved key, as the UI shows it: never the value, only label + last 4. */
    data class Entry(
        val slot: String,
        val label: String,
        val last4: String,
        val active: Boolean,
        val addedMs: Long,
    )

    /** What removing a key changed, so the caller can mirror it upstream. */
    data class RemoveResult(
        val removed: Boolean,
        /** Set when the removed key was active and another key took over. */
        val promoted: Entry? = null,
        /** The promoted key's value (to `PUT /auth/...`), when [promoted] is set. */
        val promotedKey: String? = null,
        /** True when the provider now has no keys at all (caller deletes upstream auth). */
        val empty: Boolean = false,
    )

    private fun slotName(providerId: String, slot: String): String =
        SecretNames.requireValid(SecretNames.providerSecretName(providerId) + ":" + slot)

    private fun legacyName(providerId: String): String = SecretNames.providerSecretName(providerId)

    private fun doc(): JSONObject = try {
        val raw = loadMeta()
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun providerObj(doc: JSONObject, providerId: String): JSONObject =
        doc.optJSONObject(providerId) ?: JSONObject().also {
            it.put("active", "")
            it.put("keys", JSONArray())
        }

    private fun save(doc: JSONObject) = saveMeta(doc.toString())

    private fun entriesOf(p: JSONObject): List<Entry> {
        val active = p.optString("active", "")
        val arr = p.optJSONArray("keys") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val slot = o.optString("slot", "")
            if (slot.isEmpty()) return@mapNotNull null
            Entry(
                slot = slot,
                label = o.optString("label", slot),
                last4 = o.optString("last4", ""),
                active = slot == active,
                addedMs = o.optLong("addedMs", 0L),
            )
        }
    }

    private fun writeEntries(p: JSONObject, entries: List<Entry>, active: String) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("slot", e.slot)
                    .put("label", e.label)
                    .put("last4", e.last4)
                    .put("addedMs", e.addedMs),
            )
        }
        p.put("keys", arr)
        p.put("active", active)
    }

    private fun last4Of(value: String): String = value.takeLast(4)

    /**
     * The saved keys for a provider, adopting a pre-keyring single key as
     * "Key 1" (active) when metadata does not know the provider yet.
     */
    @Synchronized
    fun entries(providerId: String): List<Entry> {
        val d = doc()
        val p = d.optJSONObject(providerId)
        if (p != null) return entriesOf(p)
        val legacy = vault.get(legacyName(providerId)) ?: return emptyList()
        // Adopt: the existing key becomes slot k1, active, value copied to its
        // own blob so a later ADD cannot orphan it.
        val entry = Entry("k1", "Key 1", last4Of(legacy), active = true, addedMs = 0L)
        vault.put(slotName(providerId, "k1"), legacy)
        val obj = providerObj(d, providerId)
        writeEntries(obj, listOf(entry), "k1")
        d.put(providerId, obj)
        save(d)
        return listOf(entry)
    }

    /** The active entry, when the provider has keys. */
    fun active(providerId: String): Entry? = entries(providerId).firstOrNull { it.active }

    /**
     * Save a new key WITHOUT deleting the others (the 2026-09-24 complaint was
     * exactly that saving replaced the stored key). The new key becomes active -
     * the user just typed it to use it - and the caller pushes it upstream.
     */
    @Synchronized
    fun add(providerId: String, value: String, label: String = ""): Entry {
        val current = entries(providerId) // adopts a legacy key first
        val d = doc()
        val obj = providerObj(d, providerId)
        var n = current.size + 1
        var slot = "k$n"
        while (current.any { it.slot == slot }) {
            n += 1; slot = "k$n"
        }
        val name = if (label.isBlank()) "Key $n" else label.trim()
        val entry = Entry(slot, name, last4Of(value), active = true, addedMs = System.currentTimeMillis())
        vault.put(slotName(providerId, slot), value)
        vault.put(legacyName(providerId), value)
        writeEntries(obj, current.map { it.copy(active = false) } + entry, slot)
        d.put(providerId, obj)
        save(d)
        return entry
    }

    /**
     * Make a saved key the active one. Returns its value for the caller to
     * `PUT /auth/:providerID`, or null when the slot does not exist / its blob
     * is unreadable (never a silent success).
     */
    @Synchronized
    fun activate(providerId: String, slot: String): String? {
        val current = entries(providerId)
        if (current.none { it.slot == slot }) return null
        val value = vault.get(slotName(providerId, slot)) ?: return null
        val d = doc()
        val obj = providerObj(d, providerId)
        writeEntries(obj, current.map { it.copy(active = it.slot == slot) }, slot)
        d.put(providerId, obj)
        save(d)
        vault.put(legacyName(providerId), value)
        return value
    }

    /**
     * Delete one key. Removing the active key promotes the next saved one (the
     * caller pushes [RemoveResult.promotedKey] upstream); removing the last key
     * leaves the provider disconnected ([RemoveResult.empty] - the caller
     * mirrors that with `DELETE /auth/:providerID`).
     */
    @Synchronized
    fun remove(providerId: String, slot: String): RemoveResult {
        val current = entries(providerId)
        val victim = current.firstOrNull { it.slot == slot } ?: return RemoveResult(removed = false)
        val rest = current.filterNot { it.slot == slot }
        vault.delete(slotName(providerId, slot))
        val d = doc()
        val obj = providerObj(d, providerId)
        return if (!victim.active) {
            writeEntries(obj, rest, current.first { it.active }.slot)
            d.put(providerId, obj)
            save(d)
            RemoveResult(removed = true)
        } else if (rest.isEmpty()) {
            writeEntries(obj, emptyList(), "")
            d.put(providerId, obj)
            save(d)
            vault.delete(legacyName(providerId))
            RemoveResult(removed = true, empty = true)
        } else {
            val heir = rest.first()
            writeEntries(obj, rest.map { it.copy(active = it.slot == heir.slot) }, heir.slot)
            d.put(providerId, obj)
            save(d)
            val value = vault.get(slotName(providerId, heir.slot))
            if (value != null) vault.put(legacyName(providerId), value)
            RemoveResult(removed = true, promoted = heir.copy(active = true), promotedKey = value)
        }
    }

    /** Delete every key and the metadata (the provider-level revoke). */
    @Synchronized
    fun removeAll(providerId: String) {
        entries(providerId).forEach { vault.delete(slotName(providerId, it.slot)) }
        vault.delete(legacyName(providerId))
        val d = doc()
        d.remove(providerId)
        save(d)
    }

    /**
     * The automatic limit-switch (owner decision): move to the NEXT saved key,
     * round-robin, and return it with its value - or null when there is nothing
     * to switch to (fewer than two keys). The caller decides WHEN (a classified
     * rate/quota error) and pushes the returned value upstream; manual override
     * stays available in Settings because this only advances the same list.
     */
    @Synchronized
    fun failover(providerId: String): Pair<Entry, String>? {
        val current = entries(providerId)
        if (current.size < 2) return null
        val idx = current.indexOfFirst { it.active }
        if (idx < 0) return null
        val next = current[(idx + 1) % current.size]
        val value = activate(providerId, next.slot) ?: return null
        return next.copy(active = true) to value
    }
}
