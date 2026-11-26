package ai.opencode.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceNameTest {

    @Test
    fun subdomainsAreReducedToTheRegisteredPart() {
        assertEquals("unsplash.com", WebImagePolicy.sourceName("https://images.unsplash.com/photo-1.jpg"))
        assertEquals("unsplash.com", WebImagePolicy.sourceName("https://unsplash.com/x.png"))
        assertEquals("wikimedia.org", WebImagePolicy.sourceName("https://upload.wikimedia.org/a.png"))
    }

    @Test
    fun wwwIsDropped() {
        assertEquals("wikimedia.org", WebImagePolicy.sourceName("https://www.wikimedia.org/x.png"))
    }

    @Test
    fun countryCodeDomainsKeepTheirSecondLevel() {
        assertEquals("example.co.uk", WebImagePolicy.sourceName("https://site.example.co.uk/a.png"))
        assertEquals("b.com.au", WebImagePolicy.sourceName("https://a.b.com.au/x.png"))
        assertEquals("wikimedia.de", WebImagePolicy.sourceName("https://upload.wikimedia.de/x.png"))
    }

    @Test
    fun ipAddressesAreShownAsTheyAre() {
        assertEquals("1.2.3.4", WebImagePolicy.sourceName("https://1.2.3.4/x.png"))
    }

    @Test
    fun noHostMeansNoLabel() {
        assertNull(WebImagePolicy.sourceName("not a url"))
        assertNull(WebImagePolicy.sourceName("https://"))
    }
}
