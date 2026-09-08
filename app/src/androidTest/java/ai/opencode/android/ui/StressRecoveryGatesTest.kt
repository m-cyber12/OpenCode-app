package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.MainActivity
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.UiError
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.RuntimeStatus
import ai.opencode.android.runtime.Secrets
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Phase 8 stress/recovery gates, run inside the app process against the real
 * supervisor and the real loopback server:
 *
 *   00 key residency probe   the security-checklist item that cannot be tested
 *                            on a CI software keystore: WHERE the master key
 *                            lives (secure hardware or software) is measured on
 *                            this device and recorded, never assumed.
 *   01 provider auth failure a REAL rejected key through the REAL provider
 *                            endpoint: the UI must say "the key was rejected",
 *                            not "the service is unreachable", and the app's
 *                            own classifier must agree with what the screen
 *                            shows.
 *   02 server kill           the supervisor must notice a SIGKILLed server,
 *                            back off, restart it, and return to HEALTHY -
 *                            bounded, without a FATAL.
 *   03 lifecycle log         the supervisor's own log must show the clean
 *                            transition shape and zero illegal transitions.
 *
 * These gates never fabricate a failure: each one applies the real cause
 * (a key OpenRouter answers 401 to, a signal to the real server pid) and then
 * asserts on what the product actually reported.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StressRecoveryGatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val support = P8GateSupport(rule)
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val PROJECT = "p8-stress"
    }

    // ---- 00: key residency (measured, not assumed) ---------------------------

    @Test
    fun `00 the master key location is measured and recorded`() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // The alias is the one Secrets/SecretStore generates the AES-GCM master
        // key under (documented constant of that class).
        val entry = ks.getEntry("opencode-app-secret-master-v1", null)
        if (entry !is KeyStore.SecretKeyEntry) {
            support.skip("KEYRESIDENCY", "no master key in the AndroidKeyStore yet (runtime never provisioned one): entry=${entry?.javaClass?.simpleName ?: "null"}")
            return
        }
        val keyInfo = entry.secretKey as? android.security.keystore.KeyInfo
        val insideSecureHardware = keyInfo?.isInsideSecureHardware ?: false
        // isStrongBoxBacked() is a @SystemApi (added with StrongBox, API 30):
        // a normal app cannot even COMPILE against it, so the probe reflects.
        // The reflection itself is the test: if the method or the value is
        // unreachable, that is recorded, not swallowed.
        var strongBox: String
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            strongBox = runCatching {
                val value = keyInfo?.javaClass?.getMethod("isStrongBoxBacked")?.invoke(keyInfo)
                if (value is Boolean) value.toString() else "probe-returned-${value?.javaClass?.simpleName ?: "null"}"
            }.getOrDefault("probe-failed")
        } else {
            strongBox = "n/a(api<30)"
        }
        val hardwareBacked = runCatching {
            ai.opencode.android.security.SecretStore.get(context).isHardwareBacked()
        }.getOrDefault(false)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val detail = "masterKey=present insideSecureHardware=$insideSecureHardware strongBoxBacked=$strongBox " +
            "secretStoreIsHardwareBacked=$hardwareBacked abi=$abi device=${android.os.Build.MODEL} " +
            "api=${android.os.Build.VERSION.SDK_INT} :: " +
            if (insideSecureHardware) "key material is held by secure hardware (TEE/StrongBox) on this device"
            else "key material is in the SOFTWARE keystore on this device - the ciphertext-at-rest guarantee holds, key-extraction resistance does not (expected on emulators; a real-device run is the evidence for the hardware claim)"
        support.gate("KEYRESIDENCY", keyInfo != null, detail)
    }

    // ---- 01: a rejected key is an auth failure, never a network failure ------

    @Test
    fun `01 a rejected provider key shows as an auth failure not a network failure`() {
        val problem = support.ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            support.skip("PROVAUTH", problem)
            return
        }
        val api = support.apiOrNull() ?: run {
            support.skip("PROVAUTH", "no loopback credential available")
            return
        }

        // The product's own credential path: Keystore store + OpenCode's own
        // PUT /auth/:providerID. The key is deliberately invalid.
        val badKey = "sk-or-v1-p8provauth-invalid-key-that-the-provider-will-reject"
        Secrets.putProviderKey(context, "openrouter", badKey)
        val provisioned = runCatching { api.setProviderAuth("openrouter", badKey) }
        if (provisioned.isFailure) {
            support.gate("PROVAUTH", false, "could not push the credential to the server: ${provisioned.exceptionOrNull()?.message}")
            return
        }
        // Make the turn actually use OpenRouter, through the repository the UI
        // uses (the same call the Settings model picker makes).
        val dir = ai.opencode.android.projects.ProjectStore.get(context).active()?.path
        AppContainer.get(context).repositoryFor(dir).setModel("openrouter", support.provisionedModel)

        val known = support.knownMessageIds()
        val sent = support.sendPrompt("Say exactly: P8AUTHCHECK and nothing else.")
        if (!sent) {
            support.gate("PROVAUTH", false, "the composer would not accept the prompt")
            return
        }

        var asksAnswered = 0
        val sawError = support.waitFor(240_000) {
            asksAnswered += support.answerAnyAsk()
            support.existsTurnErrorOrBanner()
        }

        // The classifier's read of the SERVER's own words, and the screen's read.
        val serverErr = support.errorInNew(known)
        val classified = if (serverErr != null) {
            UiError.classifyTurnError(
                serverErr.errorName, serverErr.errorMessage, serverErr.errorStatus, serverErr.errorRetryable,
            )
        } else null
        val screenText = support.allLines().joinToString("\n")
        val screenSaysAuth = screenText.contains("The model key was rejected", ignoreCase = true)
        val screenSaysNetwork = screenText.contains("The model service is unreachable", ignoreCase = true)

        // Restore: the gate must not leave a rejected key behind.
        AppContainer.get(context).repositoryFor(dir).clearModel()
        runCatching { api.deleteProviderAuth("openrouter") }
        runCatching { Secrets.removeProviderKey(context, "openrouter") }

        val ok = sawError && classified == AgentAvailability.PROVIDER_AUTH && screenSaysAuth && !screenSaysNetwork
        support.gate(
            "PROVAUTH",
            ok,
            "serverSawError=${serverErr != null} serverErrorName=${serverErr?.errorName ?: "-"} " +
                "serverErrorMessage='${serverErr?.errorMessage?.take(120) ?: "-"}' serverStatus=${serverErr?.errorStatus ?: 0} " +
                "classified=${classified ?: "-"} screenSaysKeyRejected=$screenSaysAuth screenSaysUnreachable=$screenSaysNetwork " +
                "asksAnswered=$asksAnswered screenshot=${support.shot("40-p8-provauth.png")}",
        )
    }

    // ---- 02: kill the server; the supervisor must restart it, bounded -------

    @Test
    fun `02 killing the server process triggers a supervised restart to healthy`() {
        val problem = support.ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            support.skip("SERVERKILL", problem)
            return
        }
        val paths = RuntimePaths.get(context)
        val pidText = runCatching { paths.pidFile.readText().trim() }.getOrDefault("")
        val pid = pidText.toIntOrNull()
        if (pid == null) {
            support.gate("SERVERKILL", false, "no server pid on file (pidFile='${pidText.take(40)}') - the server was not started through the launcher")
            return
        }

        val manager = RuntimeManager.get(context)
        val before = manager.state.value
        // The old pid must really be alive (it is the server we will kill).
        if (!File("/proc/$pid").exists()) {
            support.gate("SERVERKILL", false, "pid $pid from the pid file is not a live process")
            return
        }

        val kill = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 $pid"))
            p.waitFor()
            p.exitValue()
        }
        if (kill.isFailure || kill.getOrNull() != 0) {
            support.gate("SERVERKILL", false, "kill -9 $pid failed: ${kill.exceptionOrNull()?.message ?: "exit=${kill.getOrNull()}"}")
            return
        }

        // Watch the supervisor's own state flow (in-process): it must route the
        // death through CRASHED_RESTARTING and never through FATAL.
        var sawCrashedRestarting = false
        var sawFatal = false
        var healthyAgain = false
        val deadline = System.currentTimeMillis() + 240_000
        var last = before
        while (System.currentTimeMillis() < deadline) {
            last = manager.state.value
            if (last.status == RuntimeStatus.CRASHED_RESTARTING) sawCrashedRestarting = true
            if (last.status == RuntimeStatus.FATAL) { sawFatal = true; break }
            if (last.status == RuntimeStatus.HEALTHY && File("/proc/$pid").exists() == false) {
                healthyAgain = true
                break
            }
            Thread.sleep(500)
        }
        // The supervisor publishes CRASHED_RESTARTING and then STARTING; at 500 ms
        // polling the CRASHED_RESTARTING tick can be missed between samples, so the
        // state flow's restartCount is the durable witness that a restart happened.
        val restartedViaCount = last.restartCount > 0 || sawCrashedRestarting
        // Health, from the app's own checker, with the Keystore credential.
        val password = support.serverPassword()
        val health = if (password != null) {
            ai.opencode.android.runtime.HealthChecker().probe(password, timeoutMs = 3000)
        } else null
        val newPid = runCatching { paths.pidFile.readText().trim().toIntOrNull() }.getOrNull()
        val oldPidDead = !File("/proc/$pid").exists()

        val ok = oldPidDead && healthyAgain && health?.healthy == true && !sawFatal && restartedViaCount
        support.gate(
            "SERVERKILL",
            ok,
            "killedPid=$pid oldPidDead=$oldPidDead sawCrashedRestarting=$sawCrashedRestarting " +
                "restartCount=${last.restartCount} healthyAgain=$healthyAgain healthHttp=${health?.code ?: -1} " +
                "sawFatal=$sawFatal newPid=${newPid ?: "-"} finalStatus=${last.status} " +
                "finalDetail='${last.detail.take(120)}'",
        )
    }

    // ---- 03: the supervisor's own log records a clean lifecycle --------------

    @Test
    fun `03 the supervisor log shows the legal transition shape only`() {
        val paths = RuntimePaths.get(context)
        val log = if (paths.runtimeLog.isFile) paths.runtimeLog.readText() else ""
        if (log.isEmpty()) {
            support.skip("LIFECYCLELOG", "no runtime.log on disk yet")
            return
        }
        val illegal = log.lines().count { it.contains("ILLEGAL_STATE_TRANSITION") }
        val starts = log.lines().count { it.contains("state -> ") && it.contains("STARTING") }
        val healthy = log.lines().count { it.contains("state -> ") && it.contains("HEALTHY") }
        val details = log.lines()
            .filter { it.contains("state -> ") }
            .map { it.substringAfter("state -> ").take(60) }
            .takeLast(8)
            .joinToString(" | ")
        val ok = illegal == 0 && starts >= 1 && healthy >= 1
        support.gate(
            "LIFECYCLELOG",
            ok,
            "illegalTransitions=$illegal startingTransitions=$starts healthyTransitions=$healthy " +
                "logBytes=${log.length} lastStates='${details}'",
        )
    }
}
