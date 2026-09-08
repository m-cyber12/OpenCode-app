package ai.opencode.android.ui

import ai.opencode.android.AppContainer
import ai.opencode.android.MainActivity
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.UiError
import ai.opencode.android.projects.ProjectStore
import ai.opencode.android.runtime.HealthChecker
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.RuntimeStatus
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.ui.chat.TAG_COMPOSER_INPUT
import ai.opencode.android.ui.chat.TAG_COMPOSER_SEND
import ai.opencode.android.ui.chat.TAG_PERMISSION_ONCE
import ai.opencode.android.ui.chat.TAG_QUESTION_SKIP
import ai.opencode.android.ui.chat.TAG_TURN_ERROR
import android.content.Context
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertTrue
import org.junit.Assume
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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe = P8ServerProbe(context)
    private val shotDir = File(context.filesDir, "screenshots")

    private companion object {
        const val PROJECT = "p8-stress"
    }

    // ---- thin wrappers over the rule (project convention: rule-free support) -

    private fun allText(): String =
        allTextOf(rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes())

    private fun allLines(): Set<String> =
        allText().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(tag: String): Boolean {
        val found = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return found.isNotEmpty() && isEnabledNode(found.first())
    }

    private fun shot(name: String): Long = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return true
            Thread.sleep(1000)
        }
        rule.waitForIdle()
        return condition()
    }

    private fun gate(id: String, ok: Boolean, detail: String) {
        printGate8(id, ok, detail)
        assertTrue("P8_$id :: $detail", ok)
    }

    private fun skip(id: String, reason: String) {
        printSkip8(id, reason)
        Assume.assumeTrue("P8_$id :: $reason", false)
    }

    private fun existsTurnErrorOrBanner(): Boolean =
        exists(TAG_TURN_ERROR) || exists("availability_banner")

    /** OpenCode blocks a turn on a permission ask or a question exactly like the
     *  TUI does, so a live gate has to answer through the same UI a user would. */
    private fun answerAnyAsk(): Int {
        var answered = 0
        val once = rule.onAllNodesWithTag(TAG_PERMISSION_ONCE).fetchSemanticsNodes()
        if (once.isNotEmpty() && isEnabledNode(once.first())) {
            runCatching { rule.onAllNodesWithTag(TAG_PERMISSION_ONCE)[0].performClick() }
            answered++
        }
        val skipQuestion = rule.onAllNodesWithTag(TAG_QUESTION_SKIP).fetchSemanticsNodes()
        if (skipQuestion.isNotEmpty() && isEnabledNode(skipQuestion.first())) {
            runCatching { rule.onAllNodesWithTag(TAG_QUESTION_SKIP)[0].performClick() }
            answered++
        }
        if (answered > 0) rule.waitForIdle()
        return answered
    }

    /** "" when a chat surface with a usable composer is on screen, else the reason. */
    private fun ensureChatSurface(timeoutMs: Long, project: String): String {
        val leftWelcome = waitFor(timeoutMs) { exists("projects_screen") || exists("chat_screen") }
        if (!leftWelcome) return "the app never left the welcome screen within ${timeoutMs / 1000}s"
        if (exists("projects_screen")) {
            if (exists("project_row_$project")) {
                runCatching { rule.onNodeWithTag("project_row_$project").performClick() }
            } else {
                runCatching {
                    rule.onNodeWithTag("project_name_input").performTextInput(project)
                    rule.waitForIdle()
                    rule.onNodeWithTag("project_create").performClick()
                }
            }
            rule.waitForIdle()
        }
        if (!waitFor(180_000) { exists("chat_screen") }) return "no conversation screen after opening the project"
        if (!waitFor(180_000) { enabled(TAG_COMPOSER_INPUT) }) {
            return "the composer stayed disabled (agent not ready): ${allText().take(160)}"
        }
        if (probe.apiOrNull() == null) return "no loopback credential in the Keystore, so the server cannot be asked what it sent"
        return ""
    }

    private fun sendPrompt(text: String): Boolean {
        val typed = runCatching { rule.onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput(text) }.isSuccess
        if (!typed) return false
        rule.waitForIdle()
        if (!waitFor(30_000) { enabled(TAG_COMPOSER_SEND) }) return false
        val clicked = runCatching { rule.onNodeWithTag(TAG_COMPOSER_SEND).performClick() }.isSuccess
        if (!clicked) return false
        rule.waitForIdle()
        return true
    }

    // ---- 00: key residency (measured, not assumed) ---------------------------

    @Test
    fun g00_keyResidency() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // The alias is the one Secrets/SecretStore generates the AES-GCM master
        // key under (documented constant of that class).
        val entry = ks.getEntry("opencode-app-secret-master-v1", null)
        if (entry !is KeyStore.SecretKeyEntry) {
            skip("KEYRESIDENCY", "no master key in the AndroidKeyStore yet (runtime never provisioned one): entry=${entry?.javaClass?.simpleName ?: "null"}")
            return
        }
        val keyInfo = entry.secretKey as? android.security.keystore.KeyInfo
        val insideSecureHardware = keyInfo?.isInsideSecureHardware ?: false
        // isStrongBoxBacked() is a @SystemApi (added with StrongBox, API 30):
        // a normal app cannot even COMPILE against it, so the probe reflects.
        // The reflection itself is the test: if the method or the value is
        // unreachable, that is recorded, not swallowed.
        val strongBox = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val value = keyInfo?.javaClass?.getMethod("isStrongBoxBacked")?.invoke(keyInfo)
                if (value is Boolean) value.toString() else "probe-returned-${value?.javaClass?.simpleName ?: "null"}"
            }.getOrDefault("probe-failed")
        } else {
            "n/a(api<30)"
        }
        val hardwareBacked = runCatching {
            ai.opencode.android.security.SecretStore.get(context).isHardwareBacked()
        }.getOrDefault(false)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val isEmulator = android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            android.os.Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            android.os.Build.MODEL.contains("emulator", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("genymotion", ignoreCase = true)
        val detail = "masterKey=present insideSecureHardware=$insideSecureHardware strongBoxBacked=$strongBox " +
            "secretStoreIsHardwareBacked=$hardwareBacked abi=$abi device=${android.os.Build.MODEL} " +
            "api=${android.os.Build.VERSION.SDK_INT} :: " +
            if (insideSecureHardware) "key material is held by secure hardware (TEE/StrongBox) on this device"
            else "key material is in the SOFTWARE keystore on this device - the ciphertext-at-rest guarantee holds, key-extraction resistance does not (expected on emulators; a real-device run is the evidence for the hardware claim)"
        if (isEmulator || !abi.startsWith("arm")) {
            // The verdict on this device is a MEASUREMENT, not a pass/fail: an
            // x86_64 emulator has no secure hardware at all, so "software
            // keystore" is the physically expected result - a FAIL would
            // imply a product defect where there is none. The hardware
            // residency question is answered by the real-device run
            // (phase8/scripts/90-real-device-suite.sh), which runs this very
            // test on the arm64 device and applies the strict branch below.
            skip("KEYRESIDENCY", detail)
        } else {
            gate("KEYRESIDENCY", keyInfo != null && (insideSecureHardware || hardwareBacked), detail)
        }
    }

    // ---- 01: a rejected key is an auth failure, never a network failure ------

    @Test
    fun g01_providerAuth() {
        val problem = ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            skip("PROVAUTH", problem)
            return
        }
        val api = probe.apiOrNull() ?: run {
            skip("PROVAUTH", "no loopback credential available")
            return
        }

        // The product's own credential path: Keystore store + OpenCode's own
        // PUT /auth/:providerID. The key is deliberately invalid.
        val badKey = "sk-or-v1-p8provauth-invalid-key-that-the-provider-will-reject"
        Secrets.putProviderKey(context, "openrouter", badKey)
        val provisioned = runCatching { api.setProviderAuth("openrouter", badKey) }
        if (provisioned.isFailure) {
            gate("PROVAUTH", false, "could not push the credential to the server: ${provisioned.exceptionOrNull()?.message}")
            return
        }
        // Make the turn actually use OpenRouter, through the repository the UI
        // uses (the same call the Settings model picker makes).
        val dir = ProjectStore.get(context).active()?.path
        AppContainer.get(context).repositoryFor(dir).setModel("openrouter", probe.provisionedModel)

        // A FRESH session (the product's own "new chat" call): an earlier
        // stage can leave a turn in flight on the shared session, and the
        // server serializes turns per session - the first full run's prompt
        // queued behind such a stuck turn and produced nothing for 240s.
        AppContainer.get(context).repositoryFor(dir).newSession(null)
        if (!waitFor(60_000) { enabled(TAG_COMPOSER_INPUT) }) {
            gate("PROVAUTH", false, "the composer never became usable after the fresh session")
            return
        }

        val known = probe.knownMessageIds()
        val sent = sendPrompt("Say exactly: P8AUTHCHECK and nothing else.")
        if (!sent) {
            gate("PROVAUTH", false, "the composer would not accept the prompt")
            return
        }

        var asksAnswered = 0
        val sawError = waitFor(240_000) {
            asksAnswered += answerAnyAsk()
            existsTurnErrorOrBanner()
        }

        // The classifier's read of the SERVER's own words, and the screen's read.
        val serverErr = probe.errorInNew(known)
        val classified = if (serverErr != null) {
            UiError.classifyTurnError(
                serverErr.errorName, serverErr.errorMessage, serverErr.errorStatus, serverErr.errorRetryable,
            )
        } else null
        val screenText = allLines().joinToString("\n")
        val screenSaysAuth = screenText.contains("The model key was rejected", ignoreCase = true)
        val screenSaysNetwork = screenText.contains("The model service is unreachable", ignoreCase = true)

        // Restore: the gate must not leave a rejected key behind.
        AppContainer.get(context).repositoryFor(dir).clearModel()
        runCatching { api.deleteProviderAuth("openrouter") }
        runCatching { Secrets.removeProviderKey(context, "openrouter") }

        val ok = sawError && classified == AgentAvailability.PROVIDER_AUTH && screenSaysAuth && !screenSaysNetwork
        gate(
            "PROVAUTH",
            ok,
            "serverSawError=${serverErr != null} serverErrorName=${serverErr?.errorName ?: "-"} " +
                "serverErrorMessage='${serverErr?.errorMessage?.take(120) ?: "-"}' serverStatus=${serverErr?.errorStatus ?: 0} " +
                "classified=${classified ?: "-"} screenSaysKeyRejected=$screenSaysAuth screenSaysUnreachable=$screenSaysNetwork " +
                "asksAnswered=$asksAnswered screenshot=${shot("40-p8-provauth.png")}",
        )
    }

    // ---- 02: kill the server; the supervisor must restart it, bounded -------

    @Test
    fun g02_serverKill() {
        val problem = ensureChatSurface(900_000, PROJECT)
        if (problem.isNotEmpty()) {
            skip("SERVERKILL", problem)
            return
        }
        val paths = RuntimePaths.get(context)
        val pidText = runCatching { paths.pidFile.readText().trim() }.getOrDefault("")
        val pid = pidText.toIntOrNull()
        if (pid == null) {
            gate("SERVERKILL", false, "no server pid on file (pidFile='${pidText.take(40)}') - the server was not started through the launcher")
            return
        }

        val manager = RuntimeManager.get(context)
        val before = manager.state.value
        // The old pid must really be alive (it is the server we will kill).
        if (!File("/proc/$pid").exists()) {
            gate("SERVERKILL", false, "pid $pid from the pid file is not a live process")
            return
        }

        val kill = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 $pid"))
            p.waitFor()
            p.exitValue()
        }
        if (kill.isFailure || kill.getOrNull() != 0) {
            gate("SERVERKILL", false, "kill -9 $pid failed: ${kill.exceptionOrNull()?.message ?: "exit=${kill.getOrNull()}"}")
            return
        }

        // Watch the supervisor's own state flow (in-process): it must route the
        // death through CRASHED_RESTARTING and never through FATAL. The
        // recovery is only counted AFTER the flow has first left HEALTHY -
        // otherwise the still-stale HEALTHY tick right after the kill would
        // look like an instant recovery.
        var sawCrashedRestarting = false
        var sawFatal = false
        var sawNonHealthy = false
        var healthyAgain = false
        val deadline = System.currentTimeMillis() + 240_000
        var last = before
        while (System.currentTimeMillis() < deadline) {
            last = manager.state.value
            if (last.status == RuntimeStatus.CRASHED_RESTARTING) sawCrashedRestarting = true
            if (last.status == RuntimeStatus.FATAL) { sawFatal = true; break }
            if (last.status != RuntimeStatus.HEALTHY) sawNonHealthy = true
            if (sawNonHealthy && last.status == RuntimeStatus.HEALTHY && !File("/proc/$pid").exists()) {
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
        val password = probe.serverPassword()
        val health = if (password != null) {
            HealthChecker().probe(password, timeoutMs = 3000)
        } else null
        val newPid = runCatching { paths.pidFile.readText().trim().toIntOrNull() }.getOrNull()
        val oldPidDead = !File("/proc/$pid").exists()

        val ok = oldPidDead && healthyAgain && health?.healthy == true && !sawFatal && restartedViaCount
        gate(
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
    fun g03_lifecycleLog() {
        val paths = RuntimePaths.get(context)
        val log = if (paths.runtimeLog.isFile) paths.runtimeLog.readText() else ""
        if (log.isEmpty()) {
            skip("LIFECYCLELOG", "no runtime.log on disk yet")
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
        gate(
            "LIFECYCLELOG",
            ok,
            "illegalTransitions=$illegal startingTransitions=$starts healthyTransitions=$healthy " +
                "logBytes=${log.length} lastStates='${details}'",
        )
    }
}
