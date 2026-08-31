package ai.opencode.android.security

/**
 * Pure (JVM-testable) name handling for [SecretStore] entries.
 *
 * Secret names come from semi-trusted input (provider ids typed in the UI, e.g.
 * "openrouter"), and they become file names under filesDir/secrets. A name must
 * therefore never be able to escape that directory, so the accepted character
 * set is tiny and explicit.
 */
object SecretNames {

    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

    /** Prefix used for per-provider API keys: `provider:<providerID>`. */
    const val PROVIDER_PREFIX = "provider:"

    fun isValid(name: String): Boolean = VALID.matches(name)

    fun requireValid(name: String): String {
        require(isValid(name)) { "invalid secret name: ${name.take(80)}" }
        return name
    }

    fun providerSecretName(providerId: String): String {
        // Provider ids are OpenCode's own identifiers (models.dev keys); keep the
        // same rules so the file name can never contain a path separator.
        require(providerId.isNotBlank() && providerId.length <= 64 &&
            providerId.all { it.isLetterOrDigit() || it in "-_." }
        ) { "invalid provider id" }
        return requireValid(PROVIDER_PREFIX + providerId)
    }

    fun providerIdOf(secretName: String): String? =
        if (secretName.startsWith(PROVIDER_PREFIX)) secretName.removePrefix(PROVIDER_PREFIX) else null

    /** On-disk blob name for a secret. */
    fun fileName(name: String): String = requireValid(name) + ".enc"
}
