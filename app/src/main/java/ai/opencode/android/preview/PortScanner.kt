package ai.opencode.android.preview

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v9.9 (owner): "the model should be able to open whatever port it needs" -
 * so the app stops assuming its own static server is the only preview source
 * and instead WATCHES the loopback for anything that answers.
 *
 * The agent proved the point by hand-rolling an HTTP server with toybox `nc`
 * on port 8080 - invisible to the v9.8 app, which only knew port 8642. This
 * scanner is the fix: a cheap connect-probe of well-known dev ports on
 * 127.0.0.1. Android denies apps `/proc/net/tcp` (SELinux, API 29+), so
 * probing is the honest, permissionless way to learn what is listening.
 *
 * A probe is a plain TCP connect with a short timeout: a closed loopback port
 * refuses instantly (no packet leaves the device), an open one accepts. The
 * scanned list is documented to the agent in the environment brief
 * (EnvironmentBrief) so both sides agree on the contract; ports outside the
 * list can still be previewed by declaring them in `.preview/serve.json`.
 */
class PortScanner(private val connectTimeoutMs: Int = 250) {

    /** The ports the app watches, documented verbatim in the environment brief. */
    fun scan(ports: List<Int> = CANDIDATE_PORTS): List<Int> = ports.filter(::isOpen)

    fun isOpen(port: Int): Boolean = runCatching {
        Socket().use { probe ->
            // IPv4 loopback by address bytes - same lesson as PreviewServer
            // (v9.8): never let a resolver pick ::1 behind our back.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            probe.connect(InetSocketAddress(loopback, port), connectTimeoutMs)
            true
        }
    }.getOrDefault(false)

    companion object {
        /**
         * The app's own static preview server first, then the ports dev
         * tooling habitually picks. Order is the display order in the door.
         */
        val CANDIDATE_PORTS: List<Int> = listOf(
            8642, 8080, 3000, 5173, 8000, 8081, 5000, 4000,
            4173, 4321, 1234, 8888, 9000, 9090, 6006,
        )
    }
}
