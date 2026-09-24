package ai.opencode.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.1: the app-side multi-key ring, proven on the JVM with an in-memory vault.
 *
 * Every behaviour here is one the owner's 2026-09-24 feedback names directly
 * (saving must not destroy the previous key; keys are individually deletable;
 * a limit switch advances to the next key automatically) or one the upgrade
 * path requires (a pre-keyring install's single key is adopted, not lost).
 */
class ProviderKeyringTest {

    private class MemVault : ProviderKeyring.Vault {
        val map = HashMap<String, String>()
        override fun put(name: String, value: String) { map[name] = value }
        override fun get(name: String): String? = map[name]
        override fun delete(name: String): Boolean = map.remove(name) != null
        override fun contains(name: String): Boolean = map.containsKey(name)
    }

    private class Fixture {
        var meta: String = ""
        val vault = MemVault()
        fun ring() = ProviderKeyring(loadMeta = { meta }, saveMeta = { meta = it }, vault = vault)
    }

    // ---- the complaint itself: adding must not destroy ------------------------

    @Test
    fun addingASecondKeyKeepsTheFirstAndActivatesTheNew() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-or-first-key-aaaa")
        ring.add("openrouter", "sk-or-second-key-bbbb")
        val entries = ring.entries("openrouter")
        assertEquals(2, entries.size)
        assertEquals(listOf("Key 1", "Key 2"), entries.map { it.label })
        // The new key is active; the old one is intact and retrievable.
        assertEquals("Key 2", entries.first { it.active }.label)
        assertEquals("sk-or-first-key-aaaa", f.vault.get("provider:openrouter:k1"))
        // The ACTIVE value is mirrored at the legacy name every old code path reads.
        assertEquals("sk-or-second-key-bbbb", f.vault.get("provider:openrouter"))
    }

    @Test
    fun last4IsMetadataOnlyAndNeverTheWholeKey() {
        val f = Fixture()
        f.ring().add("openrouter", "sk-or-first-key-aaaa")
        val e = f.ring().entries("openrouter").single()
        assertEquals("aaaa", e.last4)
        assertFalse("metadata must not contain key material", f.meta.contains("sk-or-first-key"))
    }

    // ---- upgrade path: a pre-keyring key is adopted ---------------------------

    @Test
    fun legacySingleKeyIsAdoptedAsActiveKeyOne() {
        val f = Fixture()
        f.vault.put("provider:openrouter", "sk-legacy-value-zzzz")
        val entries = f.ring().entries("openrouter")
        assertEquals(1, entries.size)
        assertEquals("Key 1", entries[0].label)
        assertTrue(entries[0].active)
        assertEquals("zzzz", entries[0].last4)
        // The value now also lives in its own slot blob, so a later add cannot orphan it.
        assertEquals("sk-legacy-value-zzzz", f.vault.get("provider:openrouter:k1"))
    }

    @Test
    fun providerWithNoKeysListsNothing() {
        assertTrue(Fixture().ring().entries("openrouter").isEmpty())
    }

    // ---- manual switch --------------------------------------------------------

    @Test
    fun activateReturnsTheValueAndMirrorsTheLegacyName() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        ring.add("openrouter", "sk-two-bbbb")
        val value = ring.activate("openrouter", "k1")
        assertEquals("sk-one-aaaa", value)
        assertEquals("k1", ring.active("openrouter")?.slot)
        assertEquals("sk-one-aaaa", f.vault.get("provider:openrouter"))
    }

    @Test
    fun activatingAnUnknownSlotIsNullNeverASilentSuccess() {
        val f = Fixture()
        f.ring().add("openrouter", "sk-one-aaaa")
        assertNull(f.ring().activate("openrouter", "k9"))
        assertEquals("k1", f.ring().active("openrouter")?.slot)
    }

    // ---- individual delete ----------------------------------------------------

    @Test
    fun deletingAnInactiveKeyLeavesTheActiveOneAlone() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        ring.add("openrouter", "sk-two-bbbb")
        val r = ring.remove("openrouter", "k1")
        assertTrue(r.removed)
        assertNull(r.promoted)
        assertFalse(r.empty)
        assertEquals(listOf("k2"), ring.entries("openrouter").map { it.slot })
        assertNull(f.vault.get("provider:openrouter:k1"))
        assertEquals("sk-two-bbbb", f.vault.get("provider:openrouter"))
    }

    @Test
    fun deletingTheActiveKeyPromotesTheNextAndReportsItsValue() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        ring.add("openrouter", "sk-two-bbbb") // active
        val r = ring.remove("openrouter", "k2")
        assertTrue(r.removed)
        assertEquals("k1", r.promoted?.slot)
        assertEquals("sk-one-aaaa", r.promotedKey)
        assertEquals("k1", ring.active("openrouter")?.slot)
        assertEquals("sk-one-aaaa", f.vault.get("provider:openrouter"))
    }

    @Test
    fun deletingTheLastKeyReportsEmptyAndClearsTheLegacyName() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        val r = ring.remove("openrouter", "k1")
        assertTrue(r.removed && r.empty)
        assertTrue(ring.entries("openrouter").isEmpty())
        assertNull(f.vault.get("provider:openrouter"))
        assertNull(f.vault.get("provider:openrouter:k1"))
    }

    @Test
    fun removeAllWipesBlobsMetadataAndLegacy() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        ring.add("openrouter", "sk-two-bbbb")
        ring.removeAll("openrouter")
        assertTrue(ring.entries("openrouter").isEmpty())
        assertTrue(f.vault.map.keys.none { it.startsWith("provider:openrouter") })
    }

    // ---- the automatic limit-switch -------------------------------------------

    @Test
    fun failoverAdvancesRoundRobinAndReturnsTheNextValue() {
        val f = Fixture()
        val ring = f.ring()
        ring.add("openrouter", "sk-one-aaaa")
        ring.add("openrouter", "sk-two-bbbb")
        ring.add("openrouter", "sk-three-cccc") // active = k3
        val hop1 = ring.failover("openrouter")
        assertEquals("k1" to "sk-one-aaaa", hop1?.let { it.first.slot to it.second })
        val hop2 = ring.failover("openrouter")
        assertEquals("k2" to "sk-two-bbbb", hop2?.let { it.first.slot to it.second })
        val hop3 = ring.failover("openrouter")
        assertEquals("k3" to "sk-three-cccc", hop3?.let { it.first.slot to it.second })
    }

    @Test
    fun failoverWithOneOrZeroKeysIsNull() {
        val f = Fixture()
        assertNull(f.ring().failover("openrouter"))
        f.ring().add("openrouter", "sk-only-aaaa")
        assertNull(f.ring().failover("openrouter"))
        assertEquals("k1", f.ring().active("openrouter")?.slot)
    }

    // ---- persistence and isolation ---------------------------------------------

    @Test
    fun metadataRoundTripsThroughAFreshInstance() {
        val f = Fixture()
        f.ring().add("openrouter", "sk-one-aaaa")
        f.ring().add("openrouter", "sk-two-bbbb")
        f.ring().activate("openrouter", "k1")
        // A new instance over the SAME persisted meta/vault (an app restart).
        val again = f.ring().entries("openrouter")
        assertEquals(listOf("k1", "k2"), again.map { it.slot })
        assertEquals("k1", again.first { it.active }.slot)
    }

    @Test
    fun providersDoNotShareRings() {
        val f = Fixture()
        f.ring().add("openrouter", "sk-or-aaaa")
        f.ring().add("google", "sk-g-bbbb")
        assertEquals(1, f.ring().entries("openrouter").size)
        assertEquals(1, f.ring().entries("google").size)
        f.ring().removeAll("google")
        assertEquals(1, f.ring().entries("openrouter").size)
        assertEquals("sk-or-aaaa", f.vault.get("provider:openrouter"))
    }

    @Test
    fun corruptMetadataIsTreatedAsEmptyNotACrash() {
        val f = Fixture()
        f.meta = "{not json"
        assertTrue(f.ring().entries("openrouter").isEmpty())
        f.ring().add("openrouter", "sk-one-aaaa")
        assertEquals(1, f.ring().entries("openrouter").size)
    }
}
