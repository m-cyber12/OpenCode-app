package ai.opencode.android.web

import java.net.InetAddress
import java.net.URL

/**
 * The guard rails for website pictures, as pure Kotlin so they can be unit-tested
 * on the JVM without Android. [WebImages] asks these before any socket.
 */
object WebImagePolicy {

    /** https, with a host that resolves only to public addresses. */
    fun allowed(url: URL): Boolean = url.protocol == "https" && isPublicHost(url.host)

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
