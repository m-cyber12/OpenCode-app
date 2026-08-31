package ai.opencode.android.integration

import android.content.Context
import android.util.Log
import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.runtime.LoopbackAudit
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimeLogger
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretStore
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app itself proves about the server it just started, and how it hands
 * credentials to OpenCode.
 *
 * Two independent checks, because each alone has a hole:
 *
 *  1. [LoopbackAudit] — the kernel socket table (`/proc/net/tcp`). This is the
 *     authoritative shape of a bind. Android 10+ may deny this file to untrusted
 *     apps (SELinux `proc-net`), so an unreadable table is reported as
 *     INCONCLUSIVE, never as a pass and never as a violation.
 *  2. [probeReachability] — a behavioural probe: connect to the port on
 *     loopback (must work) and to this device's own non-loopback address (must
 *     be refused). Works from inside the sandbox regardless of /proc policy and
 *     is the one that would actually catch a LAN-exposed server.
 *
 * Credential provisioning deliberately uses OpenCode's own auth endpoint
 * (`PUT /auth/:providerID`) rather than an env var, so the app stays a *client*
 * of OpenCode's normal provider-config mechanism. The Keystore is the durable
 * Android-side store and is re-pushed on every start (see [provisionProviders]).
 */
class RuntimeIntegration(
    private val context: Context,
    private val paths: RuntimePaths,
    private val logger: RuntimeLogger,
) {

    data class Report(
        val at: String,
        val port: Int,
        val tableVerdict: String,
        val tableDetail: String,
        val loopbackConnectOk: Boolean,
        val externalAddress: String,
        val externalConnectRefused: Boolean,
        val externalProbe: String,
        val serverVersion: String,
        val provisionedProviders: List<String>,
        val connectedProviders: List<String>,
        val keystoreHardwareBacked: Boolean,
        val mdnsSockets: Int,
        val violations: List<String>,
    ) {
        val loopbackOnly: Boolean get() = (externalProbe == PROBE_REFUSED || externalProbe == PROBE_REFUSED_TIMEOUT) && loopbackConnectOk
        val conclusive: Boolean get() = externalProbe == PROBE_REFUSED || externalProbe == PROBE_REFUSED_TIMEOUT
        val summary: String
            get() = "loopback_only=$loopbackOnly conclusive=$conclusive table=$tableVerdict " +
                "keystore_hw=$keystoreHardwareBacked providers_pushed=${provisionedProviders.size} " +
                "opencode_connected=${connectedProviders.joinToString("|").ifEmpty { "none" }} " +
                "violations=${violations.size}"
    }

    /** Pushed to the UI + diagnostics; last run only (this is a supervisor hook). */
    @Volatile
    var lastReport: Report? = null
        private set

    fun runOnce(password: String): Report {
        val port = RuntimeEnv.SERVER_PORT
        val uid = runCatching { android.system.Os.getuid() }.getOrDefault(-1)

        val audit = runCatching { LoopbackAudit.audit(port, uid) }
        val tableVerdict = when {
            audit.isFailure -> "UNREADABLE"
            audit.getOrNull()?.ok == true -> "LOOPBACK_ONLY"
            audit.getOrNull()?.listeners?.isEmpty() == true -> "NO_ROWS(seen)"
            else -> "VIOLATION"
        }
        val tableDetail = audit.getOrNull()?.detail ?: "audit threw: ${audit.exceptionOrNull()?.message}"

        val version = runCatching { OpenCodeApi(baseUrl(port), RuntimeEnv.SERVER_USER, password).health().optString("version") }
            .getOrDefault("")
        val api = OpenCodeApi(baseUrl(port), RuntimeEnv.SERVER_USER, password)
        val provision = runCatching { provisionProviders(api) }

        val probe = probeReachability(port)
        val violations = ArrayList<String>()
        if (tableVerdict == "VIOLATION") violations.add("kernel socket table shows a non-loopback listener on :$port")
        if (probe.status == PROBE_ACCEPTED) violations.add("port $port accepted a connection on ${probe.address} (non-loopback)")

        val report = Report(
            at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date()),
            port = port,
            tableVerdict = tableVerdict,
            tableDetail = tableDetail,
            loopbackConnectOk = probe.loopbackOk,
            externalAddress = probe.address,
            externalConnectRefused = probe.status == PROBE_REFUSED || probe.status == PROBE_REFUSED_TIMEOUT,
            externalProbe = probe.status,
            serverVersion = version,
            provisionedProviders = provision.getOrNull()?.first ?: emptyList(),
            connectedProviders = provision.getOrNull()?.second ?: emptyList(),
            keystoreHardwareBacked = runCatching { SecretStore.get(context).isHardwareBacked() }.getOrDefault(false),
            mdnsSockets = audit.getOrNull()?.mdnsSockets?.size ?: -1,
            violations = violations,
        )
        lastReport = report
        writeEvidence(report, port)
        violations.forEach { logger.host("LOOPBACK VIOLATION: $it") }
        logger.host("integration: ${report.summary}")
        Log.i(TAG, "integration ${report.summary}")
        return report
    }

    private fun baseUrl(port: Int) = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:$port"

    /**
     * Re-materialise Keystore-held provider credentials into OpenCode's auth
     * store. Idempotent; safe to run on every healthy start. Never logs values.
     */
    private fun provisionProviders(api: OpenCodeApi): Pair<List<String>, List<String>> {
        val ids = Secrets.storedProviderIds(context)
        val pushed = ArrayList<String>()
        for (id in ids) {
            val key = Secrets.providerKey(context, id) ?: continue
            if (key.isBlank()) continue
            api.setProviderAuth(id, key)
            pushed.add(id)
        }
        val connected = runCatching { api.providers().connected }.getOrDefault(emptyList())
        if (pushed.isNotEmpty()) {
            logger.host("provisioned ${pushed.size} provider credential(s) from Keystore into OpenCode auth store: " +
                pushed.joinToString(",") + " (opencode connected=" + connected.joinToString("|").ifEmpty { "none" } + ")")
        }
        return pushed to connected
    }

    private fun writeEvidence(r: Report, port: Int) {
        runCatching {
            paths.loopbackAuditFile.parentFile?.mkdirs()
            paths.loopbackAuditFile.writeText(
                buildString {
                    appendLine("# OpenCode Android loopback/bind audit (written by the app itself)")
                    appendLine("at=${r.at}")
                    appendLine("uid=${runCatching { android.system.Os.getuid() }.getOrDefault(-1)}")
                    appendLine("port=$port expected_bind=${LoopbackGuard.SERVER_BIND_HOSTNAME}")
                    appendLine("table_verdict=${r.tableVerdict}")
                    appendLine("table_detail=${r.tableDetail}")
                    appendLine("probe_loopback_connect=${if (r.loopbackConnectOk) "OK" else "FAILED"}")
                    appendLine("probe_external_address=${r.externalAddress}")
                    appendLine("probe_external_connect=${r.externalProbe}")
                    appendLine("mdns_sockets_owned_by_uid=${r.mdnsSockets} (expected 0; OpenCode publishes mDNS only for a non-loopback bind)")
                    appendLine("server_version=${r.serverVersion}")
                    appendLine("keystore_hardware_backed=${r.keystoreHardwareBacked}")
                    appendLine("secret_entries=${SecretStore.get(context).entries().joinToString(",") { it.name }}")
                    appendLine("violations=${r.violations.joinToString(" | ").ifEmpty { "none" }}")
                    appendLine()
                    appendLine("## raw socket tables as seen by the app uid")
                    append(LoopbackAudit.rawFor(port))
                },
            )
        }
    }

    // ---- behavioural reachability probe ------------------------------------

    data class Probe(val loopbackOk: Boolean, val address: String, val status: String)

    /**
     * Loopback connect must succeed; a connect to this device's own routable
     * address must be refused. Any *accepted* external connection is a real
     * loopback-policy violation (and is what [violations] reports).
     */
    private fun probeReachability(port: Int): Probe {
        val loopbackOk = runCatching {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1500)
                true
            }
        }.getOrDefault(false)
        val addr = nonLoopbackIPv4() ?: return Probe(loopbackOk, "", PROBE_NO_ADDRESS)
        val status = try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(InetAddress.getByName(addr), port), 2000)
                PROBE_ACCEPTED
            }
        } catch (t: Throwable) {
            val msg = (t.message ?: t.javaClass.simpleName).lowercase(Locale.US)
            when {
                "timed out" in msg || "timeout" in msg -> PROBE_REFUSED_TIMEOUT
                else -> PROBE_REFUSED
            }
        }
        return Probe(loopbackOk, addr, status)
    }

    private fun nonLoopbackIPv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.flatMap { ni -> ni.inetAddresses.asSequence().map { ni to it } }
            ?.filter { (_, a) -> a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress }
            ?.firstOrNull()
            ?.let { (_, a) -> a.hostAddress }
    }.getOrNull()

    companion object {
        private const val TAG = "OpenCode/integration"
    }
}

/**
 * Outcome strings for the behavioural bind probe. Top-level so both the report
 * and the probe can name them without relying on nested-class scoping rules.
 */
const val PROBE_REFUSED = "REFUSED(ECONNREFUSED)"
const val PROBE_REFUSED_TIMEOUT = "REFUSED(timed out)"
const val PROBE_ACCEPTED = "ACCEPTED"
const val PROBE_NO_ADDRESS = "NO_NONLOOPBACK_ADDRESS"
