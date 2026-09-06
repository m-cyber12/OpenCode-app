package ai.opencode.android.runtime

import android.content.Context
import ai.opencode.android.security.SecretNames
import ai.opencode.android.security.SecretStore
import java.security.SecureRandom

/**
 * App-owned secrets, backed by the Android Keystore.
 *
 *  * `server-password` — the HTTP Basic password of the on-device OpenCode
 *    server. Random per install, generated on first start, never shipped in the
 *    APK, never on a command line, and from Phase 5 onwards never stored in
 *    plaintext either ([SecretStore] encrypts it under a non-exportable
 *    AndroidKeyStore key; the pre-Phase-5 plaintext file is migrated and
 *    deleted).
 *  * `provider:<id>` — model provider API keys the user entered in the app's
 *    credential screen. These are the only copy the app keeps; they reach
 *    OpenCode through OpenCode's own `PUT /auth/:providerID` endpoint (see
 *    [ai.opencode.android.client.OpenCodeRepository.provisionProvider]) after
 *    the server is healthy, and are re-pushed after every start.
 *
 * There is deliberately no plaintext fallback anywhere in this class: if the
 * Keystore cannot serve the master key the password is regenerated (a fresh
 * loopback password is harmless — nothing else knows the old one) and provider
 * keys are reported as unavailable instead of being cached on disk.
 */
object Secrets {

    const val SERVER_PASSWORD = "server-password"

    private const val MIN_LEN = 20

    /**
     * @return the loopback server password, creating it if this install has none.
     */
    fun serverPassword(context: Context, paths: RuntimePaths, logger: RuntimeLogger): String {
        val store = SecretStore.get(context)
        var migrated = false

        // 1) Pre-Phase-5 layout: plaintext 0600 file. Move it into the Keystore
        //    and remove the plaintext copy (idempotent; runs once per install).
        val legacy = paths.serverPasswordFile
        if (legacy.isFile) {
            val text = runCatching { legacy.readText().trim() }.getOrDefault("")
            val alreadyMigrated = runCatching { store.get(SERVER_PASSWORD) }.getOrNull() != null
            if (text.length >= MIN_LEN && !alreadyMigrated) {
                persist(store, text, logger, "migrated legacy plaintext server-password into AndroidKeyStore")
            }
            // Deleted unconditionally: a Keystore write that failed must not leave
            // the plaintext copy behind as a "backup".
            legacy.delete()
            migrated = true
        }

        // 2) Keystore value.
        val stored = try {
            store.get(SERVER_PASSWORD)
        } catch (t: Throwable) {
            // Master key gone (app data restored, keystore cleared): the old
            // ciphertext is unrecoverable by design. Regenerate; never fall back
            // to plaintext and never keep using a value we cannot re-read.
            logger.host("server password unreadable from Keystore (${t.message}); regenerating")
            null
        }
        if (stored != null && stored.length >= MIN_LEN) {
            if (!migrated) logger.host("server password loaded from AndroidKeyStore (keystore=${store.isHardwareBacked()})")
            return stored
        }

        // 3) Fresh install.
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val pw = bytes.joinToString("") { "%02x".format(it) }
        if (persist(store, pw, logger,
                "generated fresh server password in AndroidKeyStore (keystore=${store.isHardwareBacked()})")) {
            return pw
        }
        // A Keystore that cannot *write* must not make the agent unstartable: keep an
        // ephemeral password in RAM for this run. Loopback still requires auth, nothing
        // plaintext hits disk, and the password simply has to be re-derivable next boot.
        // The Phase-5 gates (P5-04, P5-KEYSTORE, P5-G18) fail loudly instead, so this
        // resilience can never be mistaken for a passing credential story.
        logger.host(
            "WARN: server password NOT persisted (Keystore unavailable) - running this " +
                "session with an in-RAM password; provider keys cannot be re-pushed",
        )
        return pw
    }

    /** @return true when the value is durably in the Keystore. Never throws. */
    private fun persist(
        store: SecretStore,
        value: String,
        logger: RuntimeLogger,
        okMessage: String,
    ): Boolean = try {
        store.put(SERVER_PASSWORD, value)
        logger.host(okMessage)
        true
    } catch (t: Throwable) {
        logger.host("AndroidKeyStore write failed (${t.javaClass.simpleName}: ${t.message})")
        false
    }

    /** Provider ids the app has a Keystore-backed credential for. */
    fun storedProviderIds(context: Context): List<String> =
        SecretStore.get(context).entries().mapNotNull { SecretNames.providerIdOf(it.name) }

    /**
     * The stored key, or null when there is no entry *or* the entry cannot be
     * opened (master key cleared, blob copied from another install). A corrupt
     * blob must not take down the supervisor: the caller treats null as "this
     * provider has no app-held credential" and the user re-enters it.
     */
    fun providerKey(context: Context, providerId: String): String? = try {
        SecretStore.get(context).get(SecretNames.providerSecretName(providerId))
    } catch (t: SecretStore.StoreException) {
        null
    }

    fun putProviderKey(context: Context, providerId: String, key: String) {
        SecretStore.get(context).put(SecretNames.providerSecretName(providerId), key)
    }

    fun removeProviderKey(context: Context, providerId: String): Boolean =
        SecretStore.get(context).delete(SecretNames.providerSecretName(providerId))
}
