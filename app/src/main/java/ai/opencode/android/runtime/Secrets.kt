package ai.opencode.android.runtime

import java.io.File
import java.security.SecureRandom

/**
 * App-generated secrets. The server password is random per install, stored in
 * app-private storage (mode 600) — it never ships in the APK and never appears
 * on a command line. The model key is optional (Phase 4 needs none; a user may
 * drop it in filesDir/secrets/openrouter-api-key for model gates).
 */
object Secrets {

    fun ensureServerPassword(paths: RuntimePaths, logger: RuntimeLogger): String {
        val f = paths.serverPasswordFile
        if (f.isFile && f.readText().trim().length >= 20) return f.readText().trim()
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val pw = bytes.joinToString("") { "%02x".format(it) }
        f.writeText(pw)
        runCatching { f.setReadable(true, true); f.setWritable(true, true) }
        logger.host("generated fresh server password (stored 600 at ${f.name})")
        return pw
    }

    fun readApiKey(paths: RuntimePaths): String? = try {
        if (paths.apiKeyFile.isFile) paths.apiKeyFile.readText().trim().ifEmpty { null } else null
    } catch (_: Throwable) {
        null
    }
}
