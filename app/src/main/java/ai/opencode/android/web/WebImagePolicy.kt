package ai.opencode.android.web

import java.net.InetAddress
import java.net.URL

/**
 * The guard rails for website pictures, as pure Kotlin (2026-11-23) so they can be
 * unit-tested on the JVM without Android. [WebImages] asks these before any socket.
 */
object WebImagePolicy {

    /** https, with a host that resolves only to public addresses. */
    fun allowed(url: URL): Boolean = url.protocol == "https" && isPublicHost(url.host)

    /** Second-level labels that mark a country-code domain such as example.co.uk. */
    private val registeredSecondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac")

    /**
     * The short site name shown under a picture: the host without "www.", reduced to its
     * registered part. images.unsplash.com -> unsplash.com, upload.wikimedia.org ->
     * wikimedia.org, site.example.co.uk -> example.co.uk. IP addresses are shown as they are.
     * Null when the URL has no host.
     */
    fun sourceName(url: String): String? {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
        if (host.isNullOrBlank()) return null
        val parts = host.split('.')
        if (parts.size <= 2 || parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) return host
        val keep = if (parts.last().length == 2 && parts[parts.size - 2] in registeredSecondLevel) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }

    /** True only when every address the host resolves to is a public one. */
    fun isPublicHost(host: String): Boolean {
        if (host.isBlank()) return false
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        if (addresses.isNullOrEmpty()) return false
        return addresses.none { a ->
            val raw = a.address
            a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress ||
                (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) // IPv6 unique-local
        }
    }
}
