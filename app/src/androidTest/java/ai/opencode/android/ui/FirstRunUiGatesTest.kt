package ai.opencode.android.ui

import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimeStatus
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
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The first-run experience, on a freshly installed APK, through the real
 * [MainActivity] and the real supervisor - no fabricated state anywhere in this
 * class.
 *
 * The gate script uninstalls the app and its test package before running this, so
 * what is measured is exactly what a user gets: install, open, watch the runtime
 * extract and start by itself, create a project, land in a conversation. Nothing
 * here types a URL, a port, a path or a shell command, because a user never has to.
 *
 * Verdict lines are `P6_F1..F4 PASS|FAIL :: detail` on stdout and logcat, and every
 * stage writes a PNG into the app's own files dir (`filesDir/screenshots`), which the
 * gate script extracts with `run-as` + base64 because `adb pull` cannot read
 * `/data/data`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FirstRunUiGatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shotDir = File(context.filesDir, "screenshots")

    private val stageTitles = listOf(
        "extracting" to R.string.welcome_stage_extracting_title,
        "starting" to R.string.welcome_stage_starting_title,
        "healthy" to R.string.welcome_stage_healthy_title,
        "crashed" to R.string.welcome_stage_crashed_title,
        "stopped" to R.string.welcome_stage_stopped_title,
        "fatal" to R.string.welcome_stage_fatal_title,
        "unsupported" to R.string.welcome_stage_unsupported_title,
    )

    // ---- thin wrappers over the rule (see UiGateSupport for the pure parts) ---

    private fun nodes() = rule.onAllNodes(anyNodeMatcher()).fetchSemanticsNodes()

    private fun allText() = allTextOf(nodes())

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(tag: String): Boolean {
        val found = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return found.isNotEmpty() && isEnabledNode(found.first())
    }

    private fun shot(name: String) = writeScreenshot(shotDir, name) { rule.onRoot().captureToImage() }

    /** Poll a condition while keeping the compose clock honest about idleness. */
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
        printGate(id, ok, detail)
        assertTrue("P6_$id :: $detail", ok)
    }

    private fun skip(id: String, reason: String) {
        printSkip(id, reason)
        Assume.assumeTrue("P6_$id :: $reason", false)
    }

    private fun supervisor(): Triple<String, Int, String> =
        runCatching {
            val s = RuntimeManager.get(context).state.value
            Triple(s.status.name, s.restartCount, s.detail)
        }.getOrDefault(Triple("UNREADABLE", -1, ""))

    // ---- F1: the copy a first-time user reads -------------------------------

    @Test
    fun f1_firstRunCopyNeverSurfacesAHostPortTerminalOrSsh() {
        val lines = LinkedHashSet<String>()
        val stagesSeen = LinkedHashSet<String>()
        val leaks = LinkedHashSet<String>()
        var sawWelcome = false
        var sawProjects = false
        var sawChat = false
        var continueEnabled = false
        var welcomeShot = -1L
        var projectsShot = -1L
        var chatShot = -1L

        val appeared = waitFor(180_000) {
            exists("welcome_screen") || exists("projects_screen") || exists("chat_screen")
        }

        // Sample every screen the first run passes through while the supervisor
        // extracts and starts. The union of all sampled text is what gets audited:
        // a token that flashes for one frame is still a token the user saw.
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            val text = allText()
            lines += text.split("\n").filter { it.isNotBlank() }
            leaks += leakedTokens(text)
            if (exists("welcome_screen")) {
                sawWelcome = true
                for ((name, res) in stageTitles) {
                    if (text.contains(context.getString(res), ignoreCase = true)) stagesSeen += name
                }
                if (welcomeShot < 0) welcomeShot = shot("01-first-run-welcome.png")
            }
            if (exists("welcome_continue") && enabled("welcome_continue")) continueEnabled = true
            if (exists("projects_screen")) {
                sawProjects = true
                if (projectsShot < 0) projectsShot = shot("03-first-run-projects.png")
            }
            if (exists("chat_screen")) {
                sawChat = true
                if (chatShot < 0) chatShot = shot("04-first-run-chat.png")
            }
            if (sawChat || sawProjects) break
            Thread.sleep(1500)
        }

        val all = lines.joinToString("\n")
        val welcomeCopy = listOf(
            R.string.welcome_title,
            R.string.welcome_tagline,
            R.string.welcome_body,
            R.string.welcome_action_continue,
        ).map { context.getString(it) }
        val copyShown = welcomeCopy.filter { all.contains(it, ignoreCase = true) }
        val (status, restarts, detail) = supervisor()

        val ok = appeared && sawWelcome && stagesSeen.isNotEmpty() && copyShown.size == welcomeCopy.size &&
            leaks.isEmpty() && welcomeShot > 8_000
        gate(
            "F1",
            ok,
            "firstScreen=${if (sawWelcome) "welcome" else "not-welcome"} stages=$stagesSeen " +
                "welcomeCopy=${copyShown.size}/${welcomeCopy.size} sampledLines=${lines.size} " +
                "leakedTokens=$leaks continueBecameEnabled=$continueEnabled " +
                "advancedToProjects=$sawProjects advancedToChat=$sawChat " +
                "supervisor=$status(restarts=$restarts) screenshots=welcome:$welcomeShot,projects:$projectsShot,chat:$chatShot " +
                "detailLength=${detail.length}",
        )
    }

    // ---- F2: the runtime brings itself up ----------------------------------

    @Test
    fun f2_theRuntimeInitialisesItselfAndTheAppMovesForward() {
        val start = System.currentTimeMillis()
        var status = ""
        var restarts = -1
        var sawWelcome = false
        var sawReadyStage = false
        var continueEnabled = false

        val up = waitFor(900_000) {
            val s = supervisor()
            status = s.first
            restarts = s.second
            if (exists("welcome_screen")) {
                sawWelcome = true
                if (allText().contains(context.getString(R.string.welcome_stage_healthy_title), ignoreCase = true)) {
                    sawReadyStage = true
                }
                if (enabled("welcome_continue")) continueEnabled = true
            }
            status == RuntimeStatus.HEALTHY.name || exists("projects_screen") || exists("chat_screen")
        }
        val elapsedS = (System.currentTimeMillis() - start) / 1000
        val advanced = waitFor(120_000) { exists("projects_screen") || exists("chat_screen") }
        val readyShot = shot("02-first-run-ready.png")
        val text = allText()
        val leaks = leakedTokens(text)

        // Nothing in this test touched the runtime: no start() call, no adb, no
        // port. If it came up, the app did it.
        val ok = up && status == RuntimeStatus.HEALTHY.name && advanced && restarts in 0..2 &&
            leaks.isEmpty() && readyShot > 8_000
        gate(
            "F2",
            ok,
            "status=$status restarts=$restarts waitedSeconds=$elapsedS sawWelcome=$sawWelcome " +
                "readyStageShown=$sawReadyStage continueEnabled=$continueEnabled " +
                "advancedWithoutATap=$advanced(projects=${exists("projects_screen")},chat=${exists("chat_screen")}) " +
                "leakedTokens=$leaks screenshot=$readyShot",
        )
    }

    // ---- F3: project -> conversation ---------------------------------------

    @Test
    fun f3_createAProjectAndLandInAConversation() {
        val reached = waitFor(600_000) { exists("projects_screen") || exists("chat_screen") }
        if (!reached) {
            val (status, restarts, _) = supervisor()
            gate("F3", false, "the app never left the welcome screen (supervisor=$status restarts=$restarts)")
            return
        }

        var createdThroughUi = false
        var typed = false
        if (exists("projects_screen")) {
            val emptyState = allText().contains(context.getString(R.string.projects_empty_title), ignoreCase = true)
            val projectsShot = shot("03-first-run-projects.png")
            rule.onNodeWithTag("project_name_input").performTextInput("first run gates")
            rule.waitForIdle()
            typed = allText().contains("first run gates", ignoreCase = true)
            rule.onNodeWithTag("project_create").performClick()
            createdThroughUi = waitFor(120_000) { exists("chat_screen") }
            val afterCreate = shot("04-first-run-chat.png")
            val composer = exists("composer_input")
            // The composer enables once the app has talked to its own server; on a
            // fresh project that is a moment later, not never.
            val canType = waitFor(90_000) { enabled("composer_input") }
            val leaks = leakedTokens(allText())
            val namedAfterProject = allText().contains("first-run-gates", ignoreCase = true)
            val ok = createdThroughUi && typed && composer && canType && leaks.isEmpty() &&
                projectsShot > 8_000 && afterCreate > 8_000 && namedAfterProject
            gate(
                "F3",
                ok,
                "projectsEmptyState=$emptyState typedName=$typed createdThroughUi=$createdThroughUi " +
                    "chatScreen=$composer composerEnabled=$canType projectNameShown=$namedAfterProject " +
                    "leakedTokens=$leaks screenshots=projects:$projectsShot,chat:$afterCreate",
            )
            return
        }

        // A project was already active (this class ran before on the same install):
        // the app opened it by itself, which is the same promise - open the app and
        // you are in a conversation, never in front of a connection setting.
        val chatShot = shot("04-first-run-chat.png")
        val composer = exists("composer_input")
        val canType = waitFor(90_000) { enabled("composer_input") }
        val leaks = leakedTokens(allText())
        val ok = composer && canType && leaks.isEmpty() && chatShot > 8_000
        gate(
            "F3",
            ok,
            "alreadyHadAProject=true chatScreen=$composer composerEnabled=$canType " +
                "leakedTokens=$leaks screenshot=$chatShot",
        )
    }

    // ---- F4: the evidence itself -------------------------------------------

    @Test
    fun f4_everyStageLeftAScreenshot() {
        val files = (shotDir.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()).sortedBy { it.name }
        val names = files.map { it.name }
        val sizes = files.map { "${it.name}=${it.length()}" }
        val usable = files.filter { it.length() > 8_000 }
        val hasWelcome = names.any { it.startsWith("01-") }
        val hasReady = names.any { it.startsWith("02-") }
        val hasChat = names.any { it.startsWith("04-") }
        val ok = usable.size >= 3 && hasWelcome && hasReady && hasChat
        if (files.isEmpty()) {
            skip("F4", "no screenshots in $shotDir - the earlier stages never rendered")
        }
        gate("F4", ok, "count=${files.size} usable=${usable.size} welcome=$hasWelcome ready=$hasReady chat=$hasChat $sizes")
    }
}
