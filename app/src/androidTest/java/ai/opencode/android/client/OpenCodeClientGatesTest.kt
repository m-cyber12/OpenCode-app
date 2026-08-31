package ai.opencode.android.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.RuntimeVersion
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretNames
import ai.opencode.android.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 5 on-device integration gates, driven by the APP'S OWN client code.
 *
 * Not unit tests, and not a host-side re-implementation of the gates: they run
 * inside the app's process (instrumentation), against the OpenCode server the
 * app's supervisor actually started on 127.0.0.1, using [OpenCodeApi] /
 * [OpenCodeEventStream] / [SecretStore] exactly as the UI does. Each test maps
 * to a re-run of a Phase 3/4 gate or to a Phase 5 requirement:
 *
 *   K1 health                    <- G6 re-run
 *   K2 shell tool + streaming    <- G7 re-run
 *   K3 event stream              <- G11 re-run
 *   K4 MCP local (stdio)         <- G10 re-run
 *   K5 permission ask + reply    <- G12 re-run (strict: requires a real ask)
 *   K6 Keystore-backed secrets   <- credentials requirement
 *   K7 loopback-only bind        <- network requirement
 *   K8 credential provisioning   <- credentials requirement
 *   K9 durable config PATCH      <- platform-only path a JVM harness cannot reach
 *
 * Every test also prints a machine-readable `P5_*` line (logcat + stdout) that
 * the CI gate script folds into GATES_SUMMARY.txt.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeClientGatesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val paths = RuntimePaths.get(context)
    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"
    private val workdir = File(paths.workspaces, "gates").absolutePath

    private fun password(): String {
        val pw = SecretStore.get(context).get(Secrets.SERVER_PASSWORD)
        assertNotNull("no Keystore-held server password (is the app's runtime started?)", pw)
        return pw!!
    }

    private fun api(): OpenCodeApi = OpenCodeApi(base, RuntimeEnv.SERVER_USER, password(), directory = workdir)

    private fun serverUp(): Boolean = runCatching { api().health().optBoolean("healthy") }.getOrDefault(false)

    private fun requireServer() {
        Assume.assumeTrue("skipped: the app-owned OpenCode server is not answering /global/health", serverUp())
    }

    private fun gate(name: String, ok: Boolean, detail: String = "") {
        val line = "P5_$name ${if (ok) "PASS" else "FAIL"} :: $detail"
        android.util.Log.i("OpenCode/gate", line)
        println(line)
        assertTrue(line, ok)
    }

    // ---- K1 (G6 re-run): health over the app client ------------------------

    @Test
    fun k1_healthEndpointThroughTheAndroidClient() {
        val h = api().health()
        val ok = h.optBoolean("healthy") && h.optString("version").isNotEmpty()
        gate("G6_HEALTH", ok, "body=$h")
    }

    // ---- K2 (G7 re-run): a shell command becomes a completed tool part ----

    @Test
    fun k2_shellToolRoundTrip() {
        requireServer()
        val api = api()
        val session = api.createSession("P5-K2 shell")
        val events = ConcurrentLinkedQueue<String>()
        val completed = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val stream = OpenCodeEventStream(
            base, RuntimeEnv.SERVER_USER, password(), workdir,
            onEvent = { ev ->
                if (ev.type == "message.part.updated") {
                    val part = ev.properties.optJSONObject("part")
                    if (part != null) {
                        val status = part.optJSONObject("state")?.optString("status") ?: ""
                        events.add("${part.optString("type")}:${part.optString("tool")}:$status")
                        if (part.optString("type") == "tool" && status == "completed") completed.countDown()
                    }
                }
                if (ev.type == "session.idle" && ev.properties.optString("sessionID") == session.id) {
                    idle.countDown()
                }
            },
        )
        stream.start()
        try {
            val status = api.shell(session.id, "echo P5_K2_SHELL_OK", OpenCodeRepository.SHELL_AGENT)
            assertTrue("shell accepted (HTTP $status)", status in 200..299)
            val done = completed.await(120, TimeUnit.SECONDS) || idle.await(120, TimeUnit.SECONDS)
            val messages = runCatching { api.messages(session.id) }.getOrDefault(emptyList())
            val toolParts = messages.flatMap { it.parts }.filter { it.optString("type") == "tool" }
            val output = toolParts.joinToString("\n") { p ->
                val st = p.optJSONObject("state")
                if (st == null) "" else st.optString("output") + st.optJSONObject("metadata")?.optString("output").orEmpty()
            }
            val ok = done && toolParts.isNotEmpty() &&
                toolParts.any { it.optJSONObject("state")?.optString("status") == "completed" } &&
                output.contains("P5_K2_SHELL_OK")
            gate(
                "G7_SHELL",
                ok,
                "session=${session.id} streamEvents=${events.size} toolParts=${toolParts.size} " +
                    "outputHasMarker=${output.contains("P5_K2_SHELL_OK")} last=${events.take(6)}",
            )
        } finally {
            stream.stop()
        }
    }

    // ---- K3 (G11 re-run): the event stream opens and keeps flowing -------

    @Test
    fun k3_eventStreamStreamsRealEvents() {
        requireServer()
        val api = api()
        val types = ConcurrentLinkedQueue<String>()
        val opened = CountDownLatch(1)
        val stream = OpenCodeEventStream(
            base, RuntimeEnv.SERVER_USER, password(), workdir,
            onEvent = { ev ->
                types.add(ev.type)
                opened.countDown()
            },
        )
        stream.start()
        try {
            val gotFirst = opened.await(30, TimeUnit.SECONDS)
            // Generate traffic on this instance and require the SAME subscription
            // to carry it (no reconnect, no polling of the transcript).
            val session = api.createSession("P5-K3 stream")
            val shellStatus = api.shell(session.id, "echo P5_K3_STREAM_OK", OpenCodeRepository.SHELL_AGENT)
            val deadline = System.currentTimeMillis() + 90_000
            var sawOurParts = false
            while (System.currentTimeMillis() < deadline) {
                if (types.contains("message.part.updated") && sawPartOf(session.id, api)) {
                    sawOurParts = true
                    break
                }
                Thread.sleep(300)
            }
            val busyOrIdle = types.any { it == "session.status" || it == "session.idle" }
            gate(
                "G11_STREAM",
                gotFirst && sawOurParts && busyOrIdle,
                "firstFrame=$gotFirst shell=$shellStatus statusEvents=$busyOrIdle " +
                    "streamed=${types.distinct().take(10)} total=${types.size}",
            )
        } finally {
            stream.stop()
        }
    }

    /** Confirm via REST that the server recorded a part for this session. */
    private fun sawPartOf(sessionID: String, api: OpenCodeApi): Boolean =
        runCatching { api.messages(sessionID).any { it.parts.isNotEmpty() } }.getOrDefault(false)

    // ---- K4 (G10 re-run): OpenCode's own MCP client is connected ----------

    @Test
    fun k4_mcpLocalStdioConnected() {
        requireServer()
        val status = api().mcpStatus()
        val connected = status["gates-mcp"] == "connected"
        gate("G10_MCP", connected, "status=$status (gates-mcp must be connected via stdio)")
    }

    // ---- K5 (G12 re-run): a real permission ask, answered by the app ------

    @Test
    fun k5_permissionAskIsAnsweredByTheApp() {
        requireServer()
        val api = api()
        // The harness config pins bash to "ask"; assert the policy really is in
        // effect on this instance before concluding anything about the ask.
        val bashPolicy = runCatching {
            api.globalConfig().optJSONObject("permission")?.optString("bash")
        }.getOrNull().orEmpty()
        val asks = ConcurrentLinkedQueue<JSONObject>()
        val asked = CountDownLatch(1)
        val stream = OpenCodeEventStream(
            base, RuntimeEnv.SERVER_USER, password(), workdir,
            onEvent = { ev ->
                if (EventFrame.isPermissionAsked(ev.type)) {
                    val id = ev.properties.optString("id")
                    if (id.startsWith("per_")) {
                        asks.add(ev.properties)
                        asked.countDown()
                    }
                }
            },
        )
        stream.start()
        var session = ""
        try {
            session = api.createSession("P5-K5 permission").id
            api.promptAsync(session, "Run the shell command: echo P5_K5_PERM_OK and tell me what it printed.")
            val got = asked.await(180, TimeUnit.SECONDS)
            if (!got) {
                val settled = waitForMarker(api, session, "P5_K5_PERM_OK", 60)
                gate(
                    "G12_PERMISSION",
                    false,
                    "no permission.asked frame arrived (bashPolicy='$bashPolicy', turnSettled=$settled) — " +
                        "the client approval path was never exercised",
                )
                return
            }
            val req = asks.peek()!!
            val id = req.getString("id")
            api.replyPermission(id, "once")
            val settled = waitForMarker(api, session, "P5_K5_PERM_OK", 240)
            val pendingAfterReply = runCatching { api.pendingPermissions().size }.getOrDefault(-1)
            gate(
                "G12_PERMISSION",
                settled && pendingAfterReply == 0,
                "asked permission=${req.optString("permission")} patterns=${req.optJSONArray("patterns")} " +
                    "bashPolicy=$bashPolicy replied=once markerSeen=$settled pendingAfter=$pendingAfterReply",
            )
        } finally {
            stream.stop()
            if (session.isNotEmpty()) runCatching { api.abortSession(session) }
        }
    }

    private fun waitForMarker(api: OpenCodeApi, sessionID: String, marker: String, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val msgs = runCatching { api.messages(sessionID) }.getOrDefault(emptyList())
            val text = msgs.joinToString("\n") { m -> m.parts.joinToString("\n") { p -> p.optString("text") } }
            val toolOut = msgs.flatMap { it.parts }.joinToString("\n") { p ->
                val st = p.optJSONObject("state")
                if (st == null) "" else st.optString("output") + st.optJSONObject("metadata")?.optString("output").orEmpty()
            }
            if (text.contains(marker) || toolOut.contains(marker)) return true
            Thread.sleep(2000)
        }
        return false
    }

    // ---- K6: Keystore-backed storage, ciphertext at rest ------------------

    @Test
    fun k6_secretsAreKeystoreBackedAndNeverPlaintext() {
        val store = SecretStore.get(context)
        val marker = "P5_PLAINTEXT_CANARY_" + System.currentTimeMillis()
        val name = "provider-gatecanary"
        store.put(name, marker)
        try {
            // (a) round trip through the Keystore-held key
            assertEquals("Keystore round trip must return the value", marker, store.get(name))
            // (b) ciphertext at rest: the blob must not contain the value
            val blob = File(paths.secretsDir, SecretNames.fileName(name))
            assertTrue("blob missing: $blob", blob.isFile)
            val raw = blob.readText(Charsets.ISO_8859_1)
            assertTrue("plaintext leaked into the blob", !raw.contains(marker))
            assertTrue("blob must carry IV+GCM tag overhead", blob.length() > marker.length + 16)
            // (c) the pre-Phase-5 plaintext password file must be gone
            assertTrue(
                "legacy plaintext password file must be gone",
                !paths.serverPasswordFile.exists(),
            )
            // (d) the master key cannot be exported
            val key = runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore")
                    .apply { load(null) }
                    .getKey(MASTER_ALIAS, null) as? javax.crypto.SecretKey
            }.getOrNull()
            val encoded = runCatching { key?.encoded }.getOrNull()
            assertTrue("KeyStore must refuse to export key bytes", key != null && encoded == null)
            // (e) AAD binding: the same ciphertext under a different name must not open
            val copy = File(paths.secretsDir, "gate-canary-copy.enc")
            blob.copyTo(copy, overwrite = true)
            val swapped = runCatching { store.get("gate-canary-copy") }
            copy.delete()
            assertTrue("renamed blob must fail to authenticate (AAD-bound)", swapped.isFailure)
            gate(
                "KEYSTORE",
                true,
                "hardwareBacked=${store.isHardwareBacked()} blobBytes=${blob.length()} " +
                    "entries=${store.entries().joinToString(",") { it.name }}",
            )
        } finally {
            store.delete(name)
        }
    }

    // ---- K7: loopback-only binding, probed from inside the sandbox --------

    @Test
    fun k7_serverIsReachableOnLoopbackAndRefusedElsewhere() {
        requireServer()
        val port = RuntimeEnv.SERVER_PORT
        val loopbackOk = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000); true }
        }.getOrDefault(false)

        val addrs = ArrayList<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.let { en ->
                while (en.hasMoreElements()) {
                    val ni = en.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val a = ni.inetAddresses
                    while (a.hasMoreElements()) {
                        val addr = a.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            addrs.add(addr.hostAddress)
                        }
                    }
                }
            }
        }
        var accepted = 0
        for (a in addrs) {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress(a, port), 1500); true }
            }.getOrDefault(false)
            if (open) accepted++
        }

        // Kernel table view when /proc permits it (Android 10+ may deny apps the
        // file; then it is inconclusive, never a silent pass).
        // Same row shape the in-app audit parses: "sl: local_address rem_address st ...".
        // IPv4 addresses are one little-endian word (00000000 = 0.0.0.0), IPv6 four.
        val wildcard = runCatching {
            val hex = "%04X".format(port)
            (File("/proc/net/tcp").readLines() + File("/proc/net/tcp6").readLines())
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size >= 8 && it[0].endsWith(":") }
                .filter { it[3] == "0A" }
                .filter { it[1].substringAfterLast(':').equals(hex, ignoreCase = true) }
                .count { row ->
                    val local = row[1].substringBefore(':').uppercase()
                    local == "00000000" || local == "00000000000000000000000000000000"
                }
        }.getOrDefault(-1)

        val haveExternal = addrs.isNotEmpty()
        val ok = loopbackOk && (!haveExternal || accepted == 0) && wildcard == 0
        gate(
            "LOOPBACK",
            ok,
            "loopback_connect=$loopbackOk nonLoopback=${addrs.ifEmpty { listOf("(none found)") }} " +
                "external_accepted=$accepted wildcard_listeners=$wildcard (-1=/proc/net denied to the app uid)",
        )
    }

    // ---- K8: credential provisioning reaches OpenCode's auth store --------

    @Test
    fun k8_credentialProvisioningReachesOpenCodesAuthStore() {
        requireServer()
        val api = api()
        val store = SecretStore.get(context)
        val providerId = "openrouter"
        val canary = "sk-or-P5CANARY" + System.currentTimeMillis()
        val secretName = SecretNames.providerSecretName(providerId)
        val before = api.providers().connected
        try {
            store.put(secretName, canary)
            api.setProviderAuth(providerId, canary)
            val connected = api.providers().connected
            val authFile = authStoreFile()
            val text = authFile?.let { runCatching { it.readText() }.getOrDefault("") } ?: ""
            val onDisk = authFile != null && text.contains(canary)
            val mode = authFile?.let { f ->
                runCatching { android.system.Os.stat(f.absolutePath).st_mode and 0x1FF }.getOrDefault(-1)
            } ?: -1
            gate(
                "CREDENTIALS",
                connected.contains(providerId) && onDisk,
                "keystore=yes opencode_connected=${connected.contains(providerId)} " +
                    "authJson=${authFile?.name ?: "(missing)"} onDisk=$onDisk mode=" +
                    "%03o".format(mode) + " blobBytes=${File(paths.secretsDir, SecretNames.fileName(secretName)).length()}",
            )
        } finally {
            runCatching { api.deleteProviderAuth(providerId) }
            store.delete(secretName)
        }
        val after = api.providers().connected
        assertTrue("provider still connected after revoke: $after", !after.contains(providerId))
        val text2 = authStoreFile()?.let { runCatching { it.readText() }.getOrDefault("") } ?: ""
        assertTrue("canary left behind in OpenCode's auth store", !text2.contains(canary))
        assertTrue("Keystore copy left behind", store.get(SecretNames.providerSecretName(providerId)) == null)
        // The pre-existing connection state of the install must be untouched.
        assertEquals(before, api.providers().connected)
    }

    /** OpenCode's own credential file (upstream mechanism; app-private, 0600). */
    private fun authStoreFile(): File? {
        val candidates = listOf(
            File(paths.xdgData, "opencode/auth.json"),
            File(paths.home, ".local/share/opencode/auth.json"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ---- harness support for the host-side JS gate drivers ----------------

    /**
     * Test-only export for the CI harness. The host-side JS gate drivers (the
     * re-run Phase 3/4 gates) need the loopback password but must not talk to
     * the Keystore, so — only when `files/harness/enabled` exists, created by
     * the gate script via run-as — the decrypted password is written to
     * `files/harness/server-password` (0600). No production code path ever
     * writes it, and the app never reads it.
     */
    // ---- K9: the durable config path, which only Android can exercise ------

    /**
     * `PATCH /global/config` is how the app persists MCP entries and permission modes
     * so they survive a restart (`OpenCodeRepository` calls it after every add and
     * remove). It cannot be tested on the JVM at all: `java.net.HttpURLConnection`
     * rejects the PATCH method with `ProtocolException: Invalid HTTP method: PATCH`,
     * while Android's OkHttp-backed implementation allows it - so this device gate is
     * the only real coverage that path has, and a green JVM unit-test run says
     * nothing about it.
     *
     * The payload is a *disabled* MCP entry: deep-merge means the fixture's own
     * `gates-mcp` entry is untouched, and a disabled local server spawns nothing, so
     * the probe changes no behaviour on the device.
     */
    @Test
    fun k9_durableGlobalConfigPatchRoundTrips() {
        requireServer()
        val api = api()
        val probe = "p5_patch_probe"
        runCatching {
            File(paths.workspaces, "gates").mkdirs()
        }
        val patch = JSONObject().put(
            "mcp",
            JSONObject().put(
                probe,
                JSONObject()
                    .put("type", "local")
                    .put("command", org.json.JSONArray().put("true"))
                    .put("enabled", false),
            ),
        )
        val outcome = runCatching { api.patchGlobalConfig(patch) }
        val readBack = runCatching { api.globalConfig() }
        val persisted = readBack.getOrNull()?.optJSONObject("mcp")?.has(probe) == true
        val fixtureIntact = readBack.getOrNull()?.optJSONObject("mcp")?.has("gates-mcp") ?: false
        gate(
            "K9_DURABLE_CONFIG_PATCH",
            outcome.isSuccess && persisted,
            "patch_ok=${outcome.isSuccess} persisted=$persisted fixture_mcp_intact=$fixtureIntact " +
                "err=${outcome.exceptionOrNull()?.message?.take(120)}",
        )
    }

    @Test
    fun harnessExportLoopbackCredentialForHostDrivers() {
        val marker = paths.harnessMarker
        if (!marker.isFile) {
            println("P5_HARNESS_EXPORT SKIPPED :: no ${marker.name} marker")
            return
        }
        val pw = SecretStore.get(context).get(Secrets.SERVER_PASSWORD) ?: error("no password in Keystore")
        val out = paths.harnessPasswordFile
        out.parentFile?.mkdirs()
        out.writeText(pw)
        runCatching { android.system.Os.chmod(out.absolutePath, 384) }
        // The gate script restarts the runtime right before this runs (so the
        // config fixture is picked up), which means the server may still be
        // starting. Wait for it rather than racing it: the exported file is
        // already on disk, so this loop only decides the verdict line.
        var health: org.json.JSONObject? = null
        var waited = 0
        while (waited < 120) {
            val h = runCatching { OpenCodeApi(base, RuntimeEnv.SERVER_USER, pw).health() }.getOrNull()
            if (h?.optBoolean("healthy") == true) { health = h; break }
            Thread.sleep(2000); waited += 2
        }
        val ok = health?.optBoolean("healthy") == true
        println("P5_HARNESS_EXPORT ${if (ok) "PASS" else "FAIL"} :: bytes=${out.length()} waited=${waited}s health=$health")
        assertTrue("exported credential did not authenticate the live server within 120s", ok)
    }

    @Test
    fun harnessDescribeDeviceFacts() {
        val facts = JSONObject()
            .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("payloadVersion", RuntimeVersion.PAYLOAD_VERSION)
            .put("opencode", "${RuntimeVersion.OPENCODE_VERSION}@${RuntimeVersion.OPENCODE_COMMIT.take(7)}")
            .put("keystoreHardwareBacked", runCatching { SecretStore.get(context).isHardwareBacked() }.getOrDefault(false))
            .put("secretEntries", JSONArray(SecretStore.get(context).entries().map { it.name }))
            .put("harnessDirPresent", paths.harnessDir.isDirectory)
            .put("serverHealthy", runCatching { api().health() }.getOrNull()?.toString() ?: "false")
        println("P5_DEVICE_FACTS $facts")
        runCatching { File(paths.logDir, "device-facts.json").writeText(facts.toString(2)) }
        assertTrue(true)
    }

    companion object {
        private const val MASTER_ALIAS = "opencode-app-secret-master-v1"
    }
}
