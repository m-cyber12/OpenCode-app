package ai.opencode.android.web

import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard rails for website pictures (2026-11-23), tested as pure Kotlin. No socket is
 * opened: the rules are checked on the URL and on literal addresses only.
 */
class WebImagesTest {

    @Test
    fun onlyHttpsIsAllowed() {
        assertFalse(WebImagePolicy.allowed(URL("http://example.com/a.png")))
        assertFalse(WebImagePolicy.allowed(URL("file:///sdcard/a.png")))
    }

    @Test
    fun loopbackAndPrivateAddressesAreRefused() {
        assertFalse(WebImagePolicy.isPublicHost("127.0.0.1"))
        assertFalse(WebImagePolicy.isPublicHost("10.0.0.5"))
        assertFalse(WebImagePolicy.isPublicHost("192.168.1.9"))
        assertFalse(WebImagePolicy.isPublicHost("169.254.10.1"))
        assertFalse(WebImagePolicy.isPublicHost(""))
    }

    @Test
    fun aPublicLiteralAddressIsAllowed() {
        assertTrue(WebImagePolicy.isPublicHost("8.8.8.8"))
        assertTrue(WebImagePolicy.allowed(URL("https://8.8.8.8/a.png")))
    }
}
