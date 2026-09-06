package ai.opencode.android.security

import android.content.Context
import android.system.Os
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable secret storage backed by the Android Keystore.
 *
 * Design (Phase 5 requirement: "secrets are stored via Android Keystore-backed
 * storage"):
 *
 *  * An AES-256-GCM key is generated *inside* the AndroidKeyStore provider with
 *    `setNonExportable` semantics (the framework never lets an app read back a
 *    KeyStore symmetric key: `getKey` returns a handle, and `getEncoded()`
 *    returns null). It is created with
 *    [KeyGenParameterSpec-like defaults](no user auth required, no rollback
 *    resistance, random IV required) and lives as long as the app's Keystore
 *    namespace (i.e. it survives app restarts and is destroyed by
 *    "Clear data"/uninstall).
 *  * Values never touch disk in plaintext. Each secret is stored as a separate
 *    ciphertext blob in app-private storage (mode 600):
 *
 *        OCS2 | version(2) | ivLen(2) | iv | AES-GCM ciphertext+tag
 *
 *    with the plaintext framed as `binding\nvalue` (binding =
 *    `ai.opencode.android/secret/<name>`), so a blob cannot be renamed into
 *    another slot, and cannot be forged by anyone who can only write files
 *    (GCM tag, key held by the Keystore).
 *
 * Two Keystore constraints shape this format, and both were learned the hard
 * way from the first on-device Phase-5 run (the runtime died in FATAL before
 * the server could start, taking every device gate down with it):
 *
 *  * The IV must come *from the provider*. The master key is created with
 *    `setRandomizedEncryptionRequired(true)`, and AndroidKeyStore rejects any
 *    caller-supplied IV on encryption with
 *    `InvalidAlgorithmParameterException: Caller-provided IV not permitted`.
 *    So encryption inits without a `GCMParameterSpec` and reads the generated
 *    IV back off the cipher (`cipher.iv`) to store in the header; decryption
 *    passes that IV explicitly, which is the pattern the platform documents
 *    (and what androidx security-crypto does).
 *  * GCM additional authenticated data is not relied on: AAD support for
 *    Keystore-held keys is not guaranteed across platform versions, and AAD
 *    would be passed in the same `Cipher.init` call that must stay
 *    IV-free. The name binding therefore lives inside the ciphertext, which
 *    achieves the same slot-swap protection.
 *  * There is no plaintext fallback path. If the Keystore is unavailable or the
 *    master key is gone, reads fail loudly (the caller must not silently write
 *    a plaintext copy).
 *
 * The blob dir defaults to `filesDir/secrets/` (same app-private directory the
 * runtime already uses), so backups (`allowBackup=false`), other apps and
 * `adb pull` without root cannot reach it either.
 *
 * Deliberate limits (documented, not hidden): this protects secrets at rest in
 * app-private storage; it is not a DRM boundary against a rooted device or
 * against the app's own process, and it does not make the *OpenCode-side* copy
 * of a credential (OpenCode's own `auth.json`, written by OpenCode when the app
 * provisions a provider through `PUT /auth/:providerID`) encrypted — that file
 * stays app-private mode 600 exactly as upstream defines it.
 */
class SecretStore private constructor(context: Context) {

    private val dir: File = File(context.filesDir, "secrets").apply { mkdirs() }

    class StoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Metadata about a stored secret, safe to put in diagnostics (no value). */
    data class EntryInfo(val name: String, val sizeBytes: Long, val modifiedMs: Long)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun masterKey(create: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        return generateMasterKey()
    }

    private fun generateMasterKey(): SecretKey {
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            MASTER_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // No user authentication: the runtime is started by a foreground
            // service (and by CI gates) without a prompt. Locked-out-by-auth
            // secrets would make the agent unstartable.
            .setUserAuthenticationRequired(false)
            .build()
        // Generating through the AndroidKeyStore provider with the alias in the
        // spec persists the key under that alias by itself — there is no (and
        // must be no) setEntry call: the app never holds exportable key material.
        val gen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        gen.init(spec)
        return gen.generateKey()
    }

    /**
     * True when the master key reports itself as living in secure hardware
     * (TEE/StrongBox). Reported in diagnostics because it differs between real
     * devices and emulator images (which use a software keymaster): the
     * ciphertext-at-rest guarantee holds either way, the *key extraction*
     * resistance does not.
     */
    fun isHardwareBacked(): Boolean = try {
        val key = masterKey(create = false)
        (key as? android.security.keystore.KeyInfo)?.isInsideSecureHardware ?: false
    } catch (_: Throwable) {
        false
    }

    fun contains(name: String): Boolean = blobFor(name).isFile

    fun put(name: String, value: String) {
        val key = masterKey(create = true) ?: throw StoreException(
            "AndroidKeyStore master key unavailable; refusing to store secrets in plaintext",
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Deliberately no GCMParameterSpec on this call: with a Keystore-held key
        // that requires randomized encryption the platform owns the IV, and passing
        // our own throws "Caller-provided IV not permitted" (class docs).
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw StoreException("AndroidKeyStore returned no IV for $name")
        val ct = cipher.doFinal(bound(name, value).toByteArray(Charsets.UTF_8))
        val body = ByteArray(4 + 1 + 2 + iv.size + ct.size)
        var o = 0
        System.arraycopy(MAGIC, 0, body, o, 4); o += 4
        body[o++] = VERSION.toByte()
        body[o++] = (iv.size shr 8 and 0xff).toByte()
        body[o++] = (iv.size and 0xff).toByte()
        System.arraycopy(iv, 0, body, o, iv.size); o += iv.size
        System.arraycopy(ct, 0, body, o, ct.size)
        val f = blobFor(name)
        f.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write cannot truncate an existing
        // secret; chmod before the rename so the file is never briefly readable
        // by anyone but the app uid.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { it.write(body) }
        runCatching { Os.chmod(tmp.absolutePath, 0b110000000) } // 0600
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw StoreException("could not persist secret $name")
        }
    }

    /** Returns the plaintext secret, or null when there is no entry. */
    fun get(name: String): String? {
        val f = blobFor(name)
        if (!f.isFile) return null
        val body = try { f.readBytes() } catch (t: Throwable) { throw StoreException("cannot read $name", t) }
        val key = masterKey(create = false) ?: throw StoreException(
            "AndroidKeyStore master key missing for $name (cleared app data?); no plaintext fallback",
        )
        try {
            if (body.size < 7 || String(body, 0, 4, Charsets.ISO_8859_1) != MAGIC_STR) {
                throw StoreException("bad secret blob magic for $name (corrupt, or pre-OCS2 format)")
            }
            if (body[4].toInt() != VERSION) {
                throw StoreException("unsupported secret blob version ${body[4].toInt()} for $name")
            }
            val ivLen = (body[5].toInt() and 0xff) shl 8 or (body[6].toInt() and 0xff)
            if (ivLen <= 0 || body.size < 7 + ivLen) throw StoreException("truncated secret blob for $name")
            val iv = body.copyOfRange(7, 7 + ivLen)
            val ct = body.copyOfRange(7 + ivLen, body.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val plain = String(cipher.doFinal(ct), Charsets.UTF_8)
            // Undo put()'s framing. The binding names the slot this blob may open
            // in, so a copied/renamed blob authenticates but still refuses to read.
            val nl = plain.indexOf('\n')
            if (nl < 0) throw StoreException("secret $name has no binding frame")
            if (plain.substring(0, nl) != bindingFor(name)) {
                throw StoreException("secret blob for $name is bound to a different slot")
            }
            return plain.substring(nl + 1)
        } catch (e: StoreException) {
            throw e
        } catch (t: Throwable) {
            // Wrong key (data cleared/restored), tampered blob, truncated file.
            throw StoreException("cannot decrypt secret $name: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    fun delete(name: String): Boolean = blobFor(name).delete()

    /** Names of stored entries, decrypted-value-free (for diagnostics). */
    fun entries(): List<EntryInfo> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".enc") }?.map { f ->
            EntryInfo(f.name.removeSuffix(".enc"), f.length(), f.lastModified())
        }?.sortedBy { it.name } ?: emptyList()

    /** Wipe every stored secret (leaves the Keystore master key alone). */
    fun wipe() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    /**
     * Debug/CI-only import path: write a secret from a base64 payload handed
     * over by the gate harness (`run-as … SecretStore`) instead of the UI. Kept
     * here so CI and the app use the *same* storage implementation — the gates
     * then prove the Keystore path works, not a test double.
     */
    fun putBase64(name: String, valueB64: String) {
        put(SecretNames.requireValid(name), String(Base64.decode(valueB64.trim(), Base64.DEFAULT)))
    }

    private fun blobFor(name: String): File = File(dir, SecretNames.fileName(name))

    /** `binding\nvalue`: the first line names the slot this blob may open in. */
    private fun bound(name: String, value: String): String = bindingFor(name) + "\n" + value

    private fun bindingFor(name: String): String = CTX_PREFIX + SecretNames.requireValid(name)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "opencode-app-secret-master-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION = 2   // blob format version (byte on disk)
        // OCS2: platform-generated IV + in-ciphertext name binding. Nothing was ever
        // written in OCS1 (encryption threw before it produced a blob), so there is
        // no migration to do - an OCS1 blob is simply unreadable.
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())
        private val MAGIC_STR = String(MAGIC, Charsets.ISO_8859_1)
        private const val CTX_PREFIX = "ai.opencode.android/secret/"

        @Volatile
        private var instance: SecretStore? = null

        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: SecretStore(context.applicationContext).also { instance = it }
            }
    }
}
