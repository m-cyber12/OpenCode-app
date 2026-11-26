package ai.opencode.android.web

import java.net.URL

/**
 * Finds the main picture of a web page (2026-11-26, owner: a page link should show its
 * picture, in the background and fast). Pure Kotlin, so the JVM tests can check it.
 *
 * Only the page's metadata is read: og:image first, then twitter:image. The result is a
 * full URL, resolved against the page address. It is not trusted: [WebImages] checks it
 * with [WebImagePolicy] and the picture's own content type before anything is drawn.
 */
object PagePicture {
    private val META = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val KEY = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val CONTENT = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /** Best first. */
    private val WANTED = listOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")

    /** The picture address the page names, or null when it names none. */
    fun find(pageUrl: String, html: String): String? {
        val found = HashMap<String, String>()
        for (tag in META.findAll(html)) {
            val key = KEY.find(tag.value)?.groupValues?.get(1)?.trim()?.lowercase() ?: continue
            if (key !in WANTED || found.containsKey(key)) continue
            val content = CONTENT.find(tag.value)?.groupValues?.get(1)?.trim().orEmpty()
            if (content.isNotEmpty()) found[key] = decodeEntities(content)
        }
        val raw = WANTED.firstNotNullOfOrNull { found[it] } ?: return null
        return runCatching { URL(URL(pageUrl), raw).toString() }.getOrNull()
    }

    private fun decodeEntities(s: String): String =
        s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
