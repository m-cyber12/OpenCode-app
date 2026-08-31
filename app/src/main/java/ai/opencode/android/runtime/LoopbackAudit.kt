package ai.opencode.android.runtime

import java.io.File

/**
 * Verifies — from inside the app sandbox, where the server actually runs — that
 * the OpenCode listener is bound to loopback only, and that no mDNS multicast
 * socket was opened for it.
 *
 * Why /proc/net/tcp and not just "I passed hostname=127.0.0.1": a config value
 * proves intent, the kernel's socket table proves behaviour. A wildcard bind
 * shows up here as local address `0.0.0.0:4111` (`00000000:100F`) / `[::]:4111`,
 * a loopback bind as `127.0.0.1:4111` (`0100007F:100F`). This is the exact
 * evidence Phase 5 requires for "loopback-only binding confirmed".
 *
 * Parsing is pure functions over the file text so the byte-level rules are unit
 * tested on the JVM; [audit] just supplies the real files.
 */
object LoopbackAudit {

    /** One row of /proc/net/{tcp,tcp6,udp}. */
    data class Socket(
        val table: String,        // "tcp" | "tcp6" | "udp"
        val localAddress: String, // canonical text form, e.g. "127.0.0.1" or "::"
        val localPort: Int,
        val state: String,        // hex state for TCP, "" for UDP
        val uid: Int,
        val raw: String,
    ) {
        val listening: Boolean get() = table.startsWith("tcp") && state.equals("0A", ignoreCase = true)
        val loopback: Boolean get() = localAddress == "::1" || localAddress.startsWith("127.")
        val wildcard: Boolean get() = localAddress == "0.0.0.0" || localAddress == "::"
    }

    data class Result(
        val port: Int,
        val listeners: List<Socket>,
        val mdnsSockets: List<Socket>,
        val ok: Boolean,
        val detail: String,
    )

    const val MDNS_PORT = 5353

    /** TCP state for LISTEN, per the kernel uapi header. */
    private const val TCP_LISTEN = "0A"

    fun parseTcpLines(lines: List<String>, table: String): List<Socket> = lines.mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        // Header line, or a short/bogus row: skip rather than throw.
        if (f.size < 8 || !f[0].endsWith(":")) return@mapNotNull null
        val local = f[1]
        val idx = local.lastIndexOf(':')
        if (idx <= 0) return@mapNotNull null
        val addrHex = local.substring(0, idx)
        val portHex = local.substring(idx + 1)
        val port = portHex.toLongOrNull(16)?.toInt() ?: return@mapNotNull null
        Socket(
            table = table,
            localAddress = formatAddress(addrHex, table == "tcp6" || table == "udp6"),
            localPort = port,
            state = f.getOrNull(3) ?: "",
            uid = f.getOrNull(7)?.toIntOrNull() ?: -1,
            raw = line.trim(),
        )
    }

    /**
     * The proc-net tables print IPv4 as one little-endian 32-bit word (8 hex
     * chars) and IPv6 as four little-endian 32-bit words (32 hex chars).
     */
    fun formatAddress(hex: String, v6: Boolean): String {
        if (!v6) {
            if (hex.length != 8 || !hex.all { it in "0123456789abcdefABCDEF" }) return hex
            val b = (0 until 4).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            // words are printed most-significant-byte-first of the *little-endian*
            // word, so reverse them to network order.
            return "${b[3]}.${b[2]}.${b[1]}.${b[0]}"
        }
        if (hex.length != 32) return hex
        val words = (0 until 4).map { hex.substring(it * 8, it * 8 + 8) }
        val bytes = ArrayList<Int>(16)
        for (w in words) {
            val b = (0 until 4).map { w.substring(it * 2, it * 2 + 2).toInt(16) }
            bytes.addAll(listOf(b[3], b[2], b[1], b[0]))
        }
        // Collapse leading/trailing zeros the way the kernel's %pI6 style does for
        // the two cases that matter here (::, ::1), otherwise print full form.
        val allZero = bytes.all { it == 0 }
        if (allZero) return "::"
        if (bytes.take(15).all { it == 0 } && bytes[15] == 1) return "::1"
        // IPv4-mapped (::ffff:a.b.c.d) shows up as such on dual-stack sockets.
        if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
            return "::ffff:${bytes[12]}.${bytes[13]}.${bytes[14]}.${bytes[15]}"
        }
        return (0 until 8).joinToString(":") { i ->
            "%x".format(bytes[i * 2] * 256 + bytes[i * 2 + 1])
        }
    }

    /** Read the real tables. Missing files (locked-down kernel) yield empty lists. */
    private fun readTable(path: String, table: String): List<Socket> = try {
        parseTcpLines(File(path).readLines().drop(1), table)
    } catch (_: Throwable) {
        emptyList()
    }

    fun audit(port: Int, uid: Int = -1, includeAllUids: Boolean = true): Result {
        val tcp = readTable("/proc/net/tcp", "tcp")
        val tcp6 = readTable("/proc/net/tcp6", "tcp6")
        val udp = readTable("/proc/net/udp", "udp")
        val udp6 = readTable("/proc/net/udp6", "udp6")
        val all = tcp + tcp6 + udp + udp6
        val ours = if (uid >= 0 && !includeAllUids) all.filter { it.uid == uid } else all
        val listeners = ours.filter { it.state == TCP_LISTEN && it.localPort == port }
        // mDNS would be a UDP socket bound to 5353 (224.0.0.251 multicast) owned
        // by this uid — that is what a non-loopback publish would leave behind.
        val mdns = ours.filter { (it.table == "udp" || it.table == "udp6") && it.localPort == MDNS_PORT }
            .let { l -> if (uid >= 0) l.filter { it.uid == uid } else l }
        val loopbackOnly = listeners.isNotEmpty() && listeners.all { it.loopback }
        val detail = buildString {
            append("port=$port listeners=${listeners.size} loopback_only=$loopbackOnly ")
            append("mdns_sockets=${mdns.size} ")
            append("binds=[${listeners.joinToString(",") { "${it.table}:${it.localAddress}:${it.localPort}:uid${it.uid}" }}]")
            if (mdns.isNotEmpty()) {
                append(" mdns=[${mdns.joinToString(",") { "${it.table}:${it.localAddress}:${it.localPort}:uid${it.uid}" }}]")
            }
        }
        return Result(port, listeners, mdns, ok = listeners.isNotEmpty() && loopbackOnly && mdns.isEmpty(), detail = detail)
    }

    /** Raw table rows for the given port, for the evidence file. */
    fun rawFor(port: Int): String {
        val needle = ":" + "%04X".format(port)
        val sb = StringBuilder()
        for ((path, table) in listOf(
            "/proc/net/tcp" to "tcp", "/proc/net/tcp6" to "tcp6",
            "/proc/net/udp" to "udp", "/proc/net/udp6" to "udp6",
        )) {
            sb.appendLine("## $table ($path)")
            val lines = try { File(path).readLines() } catch (_: Throwable) { sb.appendLine("(unreadable)"); continue }
            val header = lines.firstOrNull() ?: ""
            val hits = lines.drop(1).filter { it.contains(needle, ignoreCase = true) }
            if (header.isNotEmpty()) sb.appendLine(header)
            if (hits.isEmpty()) sb.appendLine("(no rows referencing port $port)") else hits.forEach { sb.appendLine(it.trim()) }
        }
        return sb.toString()
    }
}
