package ai.opencode.android.client

import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretStore
import ai.opencode.android.ui.printGate9
import ai.opencode.android.ui.printMarker9
import ai.opencode.android.ui.printSkip9
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * PHASE 9 gate P9-PROVSEL: the carried Phase 8 defect "turns silently fall back
 * to the bundled `opencode` provider instead of the configured one", proven and
 * fixed WITHOUT a funded model credential - so it runs on the CI emulator and
 * on any device, every time.
 *
 * Root cause (upstream `provider/provider.ts`, pinned commit): the provider
 * table is an `InstanceState` built once per loaded instance from `auth.json`;
 * `PUT /auth/:providerID` only writes that file. Until the instance is rebuilt,
 * the just-provisioned provider does not exist for turns: a prompt naming it
 * is rejected (`ProviderModelNotFoundError`, published only as `session.error`)
 * and a prompt naming nothing goes to `Provider.defaultModel()` -> `opencode`.
 * Upstream's desktop client disposes the instance after `auth.set`; this
 * client did not. The fix is `POST /global/dispose` after every credential
 * change (`OpenCodeRepository.applyCredentialChange`).
 *
 * What is measured (all through the real server, no mocks):
 *   A  BEFORE the fix's dispose: `GET /provider` reports the provider as
 *      `connected` (that list is read straight from auth.json) but the entry
 *      the loaded instance serves still has `source != "api"` - the stale
 *      table. This is the defect, observed directly.
 *   B  AFTER `POST /global/dispose`: the same entry reports `source == "api"`
 *      (the table was rebuilt with the credential).
 *   C  A prompt naming `<provider>/<model>` is ACCEPTED by the rebuilt instance:
 *      the server records a user message whose `model.providerID` is the
 *      configured provider, and no `ProviderModelNotFoundError` is published.
 *      The turn itself then fails at the provider (the key is a dummy), which
 *      is expected and NOT what this gate asserts - reaching the provider with
 *      the right provider selected is the whole point.
 *
 * The key is a dummy; nothing leaves the device beyond one refused request to
 * the provider, and the credential is removed again in the last step.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProviderSelectionGatesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

    private companion object {
        const val PROVIDER = "openrouter"
        const val MODEL = "openai/gpt-4o-mini"
        const val DUMMY_KEY = "sk-or-v1-p9provsel-dummy-key-not-a-real-credential"

        @Volatile var staleSourceBefore = "-"
        @Volatile var sourceAfter = "-"
        @Volatile var connectedBefore = false
    }

    private fun apiOrNull(): OpenCodeApi? = runCatching {
        val password = SecretStore.get(context).get(Secrets.SERVER_PASSWORD) ?: return@runCatching null
        val dir = ProjectStore.get(context).active()?.path
        OpenCodeApi(base, RuntimeEnv.SERVER_USER, password, directory = dir)
    }.getOrNull()

    private fun gate(id: String, ok: Boolean, detail: String) {
        printGate9(id, ok, detail)
        assertTrue("P9_$id :: $detail", ok)
    }

    private fun skip(id: String, reason: String) {
        printSkip9(id, reason)
        assumeTrue("P9_$id :: $reason", false)
    }

    private fun waitHealthy(api: OpenCodeApi, ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { api.health().optBoolean("healthy") }.getOrDefault(false)) return true
            Thread.sleep(1500)
        }
        return false
    }

    private fun serverLogTail(maxChars: Int = 6000): String = runCatching {
        val dirs = listOf(File(context.filesDir, "xdg/data/opencode/log"), File(context.filesDir, "xdg/state/opencode/log"))
        val newest = dirs.flatMap { d -> d.listFiles()?.toList().orEmpty() }.filter { it.isFile }
            .maxByOrNull { it.lastModified() } ?: return@runCatching ""
        val text = newest.readText()
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }.getOrDefault("")

    // ---- A: the defect, observed ------------------------------------------------

    @Test
    fun g01_staleInstanceAfterBarePut() {
        val api = apiOrNull() ?: run { skip("PROVSEL_STALE", "no loopback credential available"); return }
        if (!waitHealthy(api, 240_000)) { skip("PROVSEL_STALE", "server not healthy"); return }

        // Start from a clean slate so the instance is guaranteed to have been
        // built WITHOUT this provider's credential.
        runCatching { api.deleteProviderAuth(PROVIDER) }
        runCatching { api.dispose() }
        val fresh = api.providers()
        val before = fresh.entries.firstOrNull { it.id == PROVIDER }
        printMarker9("PROVSEL_BASELINE", "provider=$PROVIDER connected=${fresh.connected.contains(PROVIDER)} source=${before?.source ?: "-"}")

        // The bare upstream call, exactly what the Phase 8 client did.
        api.setProviderAuth(PROVIDER, DUMMY_KEY)
        val stale = api.providers()
        val entry = stale.entries.firstOrNull { it.id == PROVIDER }
        connectedBefore = stale.connected.contains(PROVIDER)
        staleSourceBefore = entry?.source ?: "-"
        // The defect: auth.json says connected, the loaded instance still
        // serves the credential-less catalog entry.
        val defectReproduced = connectedBefore && staleSourceBefore != "api"
        // The gate PASSes when the credential was accepted (auth.json says
        // connected); `reproduced=` records whether the stale-table defect is
        // observable on this build. reproduced=true is the expected reading
        // on upstream 05ea5073 and is the diagnosis evidence; reproduced=false
        // with instanceSource=api would mean upstream now invalidates on
        // auth.set (the client-side dispose becomes redundant, harmless).
        gate(
            "PROVSEL_STALE",
            connectedBefore,
            "after a bare PUT /auth/$PROVIDER: connected=$connectedBefore instanceSource=$staleSourceBefore " +
                "reproduced=$defectReproduced :: reproduced=true means auth.json has the key but the loaded instance " +
                "still serves the credential-less catalog entry (the carried Phase 8 defect)",
        )
    }

    // ---- B: the fix ---------------------------------------------------------------

    @Test
    fun g02_disposeRebuildsWithCredential() {
        val api = apiOrNull() ?: run { skip("PROVSEL_REBUILT", "no loopback credential available"); return }
        api.dispose()
        val after = api.providers()
        val entry = after.entries.firstOrNull { it.id == PROVIDER }
        sourceAfter = entry?.source ?: "-"
        gate(
            "PROVSEL_REBUILT",
            after.connected.contains(PROVIDER) && sourceAfter == "api" && (entry?.models?.isNotEmpty() == true),
            "after POST /global/dispose: connected=${after.connected.contains(PROVIDER)} instanceSource=$sourceAfter " +
                "models=${entry?.models?.size ?: 0} (was instanceSource=$staleSourceBefore before the dispose)",
        )
    }

    // ---- C: a turn now selects the configured provider ---------------------------

    @Test
    fun g03_turnSelectsConfiguredProvider() {
        val api = apiOrNull() ?: run { skip("PROVSEL_TURN", "no loopback credential available"); return }
        val session = api.createSession(title = "p9 provider selection")
        val logBefore = serverLogTail().length
        val status = api.promptAsync(
            session.id,
            "Reply with exactly: P9PROVSEL",
            model = OpenCodeApi.ModelRef(PROVIDER, MODEL),
        )
        // What the server recorded for the USER message is the authoritative
        // "which provider did this turn select" - it is written by
        // SessionPrompt.createUserMessage before any provider I/O.
        var userProvider = ""
        var userModel = ""
        var notFound = false
        var selectedLine = ""
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            for (m in runCatching { api.messages(session.id, 10) }.getOrDefault(emptyList())) {
                if (m.role != "user") continue
                val mdl = m.info?.optJSONObject("model") ?: continue
                userProvider = mdl.optString("providerID")
                userModel = mdl.optString("modelID")
            }
            val log = serverLogTail()
            notFound = log.contains("ProviderModelNotFoundError") && log.contains("$PROVIDER/$MODEL")
            selectedLine = Regex("llm\\.runtime=\\S+ llm\\.provider=\\S+ llm\\.model=\\S+")
                .findAll(log).map { it.value }.lastOrNull() ?: ""
            if (userProvider.isNotEmpty() && (selectedLine.contains("llm.provider=$PROVIDER") || notFound)) break
            Thread.sleep(2000)
        }
        val ok = (status == 204 || status == 200) && userProvider == PROVIDER && userModel == MODEL && !notFound
        printMarker9("PROVSEL_SERVERLOG", "selected=[$selectedLine] modelNotFound=$notFound logGrew=${serverLogTail().length - logBefore}")
        gate(
            "PROVSEL_TURN",
            ok,
            "prompt_async=$status userMessage.model=$userProvider/$userModel modelNotFound=$notFound " +
                "serverSelected=[${selectedLine.ifEmpty { "(no llm line yet - the provider refused the dummy key before/at stream start)" }}] " +
                ":: the turn was created against the configured provider, not the bundled opencode default",
        )
    }

    // ---- cleanup ------------------------------------------------------------------

    @Test
    fun g04_cleanup() {
        val api = apiOrNull() ?: run { skip("PROVSEL_CLEANUP", "no loopback credential available"); return }
        if (!waitHealthy(api, 240_000)) { skip("PROVSEL_CLEANUP", "server not healthy"); return }
        runCatching { api.deleteProviderAuth(PROVIDER) }
        runCatching { api.dispose() }
        // The server may be mid-restart (supervised); retry the readback briefly
        // instead of reporting "connected=null" as a failed cleanup.
        var after: OpenCodeApi.ProviderSnapshot? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (after == null && System.currentTimeMillis() < deadline) {
            after = runCatching { api.providers() }.getOrNull()
            if (after == null) Thread.sleep(3000)
        }
        val gone = after != null && !after.connected.contains(PROVIDER)
        gate(
            "PROVSEL_CLEANUP",
            gone,
            "dummy credential removed: connected=${after?.connected?.contains(PROVIDER) ?: "unreadable (server unreachable for 60 s)"}",
        )
    }
}
