package ai.opencode.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagePictureTest {

    private val page = "https://www.example.com/photo/mountain-123/"

    @Test
    fun ogImageIsResolvedAgainstThePage() {
        val html = """<html><head><meta property="og:image" content="/img/peak.jpg"></head></html>"""
        assertEquals("https://www.example.com/img/peak.jpg", PagePicture.find(page, html))
    }

    @Test
    fun absoluteOgImageIsKept() {
        val html = """<meta property="og:image" content="https://images.example.org/a.jpg?w=1200">"""
        assertEquals("https://images.example.org/a.jpg?w=1200", PagePicture.find(page, html))
    }

    @Test
    fun ogBeatsTwitterWhateverTheOrder() {
        val html = """
            <meta name="twitter:image" content="https://cdn.example.org/t.png">
            <meta content="https://cdn.example.org/og.png" property="og:image">
        """.trimIndent()
        assertEquals("https://cdn.example.org/og.png", PagePicture.find(page, html))
    }

    @Test
    fun twitterImageIsUsedWhenThereIsNoOg() {
        val html = """<meta name="twitter:image" content="https://cdn.example.org/t.png">"""
        assertEquals("https://cdn.example.org/t.png", PagePicture.find(page, html))
    }

    @Test
    fun singleQuotesWork() {
        val html = "<meta property='og:image' content='https://cdn.example.org/s.jpg'>"
        assertEquals("https://cdn.example.org/s.jpg", PagePicture.find(page, html))
    }

    @Test
    fun ampersandEntitiesAreDecoded() {
        val html = """<meta property="og:image" content="https://cdn.example.org/a.jpg?w=1&amp;h=2">"""
        assertEquals("https://cdn.example.org/a.jpg?w=1&h=2", PagePicture.find(page, html))
    }

    @Test
    fun emptyContentIsSkipped() {
        val html = """
            <meta property="og:image" content="">
            <meta name="twitter:image" content="https://cdn.example.org/t.png">
        """.trimIndent()
        assertEquals("https://cdn.example.org/t.png", PagePicture.find(page, html))
    }

    @Test
    fun pageWithoutPictureMetadataGivesNull() {
        assertNull(PagePicture.find(page, "<html><head><title>No picture</title></head></html>"))
        assertNull(PagePicture.find(page, """<meta property="og:title" content="x">"""))
    }
}
