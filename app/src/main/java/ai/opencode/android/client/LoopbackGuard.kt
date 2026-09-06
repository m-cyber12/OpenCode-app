package ai.opencode.android.client

import java.net.InetAddress
import java.net.URI

/**
 * Single place that decides what "talk to the app's own server" means.
 *
 * Phase 5 requirement: the app must connect to the OpenCode server running on
 * the device, and that server must never be reachable from the LAN. Two
 * consequences enforced here:
 *
 *  1. Every client base URL must be a loopback literal. Anything else (a
 *     hostname that could resolve off-device, a 10.x/192.168.x address, https
 *     to some gateway) is rejected before a socket is opened, so the app can
 *     never silently turn into a thin client for a remote OpenCode server or a
 *     cloud gateway.
 *  2. The hostname the server is *told* to bind with must also be loopback.
 *     [bindHostname] is used by [ai.opencode.android.runtime.RuntimeEnv] and by
 *     the payload launcher, and it has no "expose to LAN" value in it at all:
 *     enabling LAN exposure later requires an explicit product change.
 */
object LoopbackGuard {

    val LOOPBACK_BIND_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "::1", "ip6-localhost")

    /** The only hostname the app will ever launch the server with. */
    const val SERVER_BIND_HOSTNAME = "127.0.0.1"

    /**
     * @return [requested] when it is loopback, otherwise [SERVER_BIND_HOSTNAME].
     * A non-loopback request is refused (and reported), never honoured.
     */
    fun bindHostname(requested: String?): Pair<String, String?> {
        val host = requested?.trim().orEmpty()
        if (host.isEmpty()) return SERVER_BIND_HOSTNAME to null
        if (host.lowercase() in LOOPBACK_BIND_HOSTS) return host to null
        return SERVER_BIND_HOSTNAME to
            "refused non-loopback server bind request '$host' (loopback-only policy; " +
            "LAN exposure is not enabled in this build)"
    }

    fun isLoopbackLiteral(host: String): Boolean {
        val h = host.removeSurrounding("[", "]").lowercase()
        if (h in LOOPBACK_BIND_HOSTS) return true
        return h.startsWith("127.") && h.substring(4).all { it == '.' || it.isDigit() }
    }

    /** Resolve-and-check for an arbitrary address string (used by the bind audit). */
    fun isLoopbackAddress(text: String): Boolean {
        if (isLoopbackLiteral(text)) return true
        return runCatching { InetAddress.getByName(text.removeSurrounding("[", "]")).isLoopbackAddress }.getOrDefault(false)
    }

    /**
     * Validate a client base URL. Returns the normalised `scheme://host:port`
     * (no trailing slash). Throws [IllegalArgumentException] on anything that is
     * not plain HTTP against a loopback address.
     */
    fun checked(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "empty base URL" }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("malformed OpenCode base URL: $baseUrl")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http") { "only http:// to the local server is allowed (got '${uri.scheme}')" }
        val host = uri.host
        require(!host.isNullOrEmpty()) { "missing host in base URL: $baseUrl" }
        require(isLoopbackLiteral(host)) {
            "refusing to talk to a non-loopback OpenCode server host '$host' " +
                "(the app only connects to the on-device server on 127.0.0.1)"
        }
        val port = if (uri.port > 0) uri.port else 80
        return "http://$host:$port"
    }
}
