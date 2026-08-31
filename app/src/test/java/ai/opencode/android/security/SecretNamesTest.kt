package ai.opencode.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretNamesTest {

    @Test
    fun acceptsProviderStyleNames() {
        for (n in listOf("server-password", "provider:openrouter", "provider:anthropic", "a", "x1._:-")) {
            assertTrue(n, SecretNames.isValid(n))
        }
    }

    @Test
    fun rejectsPathTraversalAndJunk() {
        for (n in listOf("", "../evil", "a/b", "a\\b", ".hidden", "-lead", "a".repeat(70), "spaced name", "a\nb", "..")) {
            assertTrue("must reject '$n'", !SecretNames.isValid(n))
            val thrown = runCatching { SecretNames.requireValid(n) }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException)
        }
    }

    @Test
    fun blobFileNameIsContained() {
        val f = SecretNames.fileName("provider:openrouter")
        assertEquals("provider:openrouter.enc", f)
        assertTrue(f.none { it == '/' || it == '\\' })
        assertEquals("server-password.enc", SecretNames.fileName("server-password"))
    }

    @Test
    fun providerIdRoundTrip() {
        val name = SecretNames.providerSecretName("openrouter")
        assertEquals("provider:openrouter", name)
        assertEquals("openrouter", SecretNames.providerIdOf(name))
        assertNull(SecretNames.providerIdOf("server-password"))
        assertTrue(runCatching { SecretNames.providerSecretName("../etc/passwd") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SecretNames.providerSecretName("") }.exceptionOrNull() is IllegalArgumentException)
    }
}
