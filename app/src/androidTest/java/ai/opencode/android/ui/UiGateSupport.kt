package ai.opencode.android.ui

import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.UiError
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimeVersion
import ai.opencode.android.ui.common.RuntimeSummary
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import java.io.File

/**
 * Helpers shared by the three Phase 6 gate classes.
 *
 * Everything here is deliberately free of any Compose test-rule type: the rule
 * implementations differ between `createComposeRule()` (a plain content rule) and
 * `createAndroidComposeRule<MainActivity>()` (the real activity), so each gate
 * class keeps its own two-line wrappers around `rule.onAllNodes(...)` and calls
 * into these pure functions. That keeps the verdict format, the screenshot format
 * and the accessibility audit identical across all three classes without coupling
 * them to a rule interface.
 */

/** Same logcat tag Phase 5's gates use, so one `logcat -s` filter catches both. */
internal const val GATE_TAG = "OpenCode/gate"

/** Print one machine-readable verdict line to logcat AND stdout (Phase 5 discipline). */
internal fun printGate(id: String, ok: Boolean, detail: String) {
    val line = "P6_$id ${if (ok) "PASS" else "FAIL"} :: $detail"
    Log.i(GATE_TAG, line)
    println(line)
}

/** A gate that could not run must say SKIP - never silence, never PASS. */
internal fun printSkip(id: String, reason: String) {
    val line = "P6_$id SKIP :: $reason"
    Log.i(GATE_TAG, line)
    println(line)
}

/** Non-verdict marker line (e.g. whether a model could serve a turn at all). */
internal fun printMarker(name: String, value: String) {
    val line = "P6_$name $value"
    Log.i(GATE_TAG, line)
    println(line)
}

/**
 * A fabricated supervisor state. [RuntimeSummary] is the value type screens
 * consume, so a gate can render any degraded state without a device runtime -
 * and [availability] is derived by the same pure classifier the app uses, not
 * hand-picked, so a fabricated state cannot disagree with itself.
 */
internal fun runtimeSummaryFixture(
    status: String,
    ready: Boolean,
    detail: String = "",
    restarts: Int = 0,
): RuntimeSummary = RuntimeSummary(
    status = status,
    detail = detail,
    restartCount = restarts,
    availability = UiError.classifyRuntimeStatus(status),
    ready = ready,
    opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
    payloadVersion = RuntimeVersion.PAYLOAD_VERSION,
    abi = "arm64-v8a",
    device = "Gate Device",
    androidVersion = "15 (API 35)",
    audit = "loopback-only bind, credential in Android Keystore",
)

/** The supervisor's own healthy detail line - it carries the loopback bind address. */
internal fun healthyDetailFixture(): String =
    "healthy on ${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

/**
 * Tokens that must never appear on the first-run or conversation surfaces. The
 * product rule is that a user never sees a terminal, a URL, a host, a port, an
 * `adb` command or an install instruction: the agent is supposed to look like an
 * app, not like a server they have to reach.
 *
 * Built from the app's own constants so the gate stays true if the port changes.
 */
internal fun forbiddenConnectionTokens(): List<String> = listOf(
    "termux",
    "ssh",
    "scp ",
    "adb ",
    "localhost",
    LoopbackGuard.SERVER_BIND_HOSTNAME,
    "http://",
    "https://",
    ":${RuntimeEnv.SERVER_PORT}",
    "port ${RuntimeEnv.SERVER_PORT}",
    "/data/data",
    "/data/user",
    "npm install",
    "bun install",
    "bash -c",
    "vscode",
)

/** Which forbidden tokens a screen actually showed (empty = clean). */
internal fun leakedTokens(text: String): List<String> =
    forbiddenConnectionTokens().filter { text.contains(it, ignoreCase = true) }

/**
 * The accessible name of a semantics node: what TalkBack would speak. Content
 * description first, then text, then the value of an editable field - the same
 * order the framework uses when it merges a node's descendants.
 */
internal fun textOf(cfg: SemanticsConfiguration): String = buildString {
    cfg.getOrNull(SemanticsProperties.ContentDescription)?.let { append(it.joinToString(" ")); append(' ') }
    cfg.getOrNull(SemanticsProperties.Text)?.let { append(it.joinToString(" ")); append(' ') }
    cfg.getOrNull(SemanticsProperties.EditableText)?.let { append(it); append(' ') }
    cfg.getOrNull(SemanticsProperties.StateDescription)?.let { append(it); append(' ') }
}.trim()

/** Every name on screen, one node per line. */
internal fun allTextOf(nodes: List<SemanticsNode>): String =
    nodes.joinToString("\n") { textOf(it.config) }

/** Result of the interactive-element audit for one screen. */
internal class Audit(val label: String, val interactive: Int, val unnamed: List<String>) {
    val clean: Boolean get() = unnamed.isEmpty()
    override fun toString(): String = "$label:interactive=$interactive,unnamed=${unnamed.size}"
}

/**
 * Every element a user can act on must have a real accessible name. The audit
 * runs over the MERGED tree, which is what a screen reader walks: a node is
 * interactive when it carries a click, a toggle state or a text entry action, and
 * it is unnamed when none of contentDescription / text / editable text /
 * state description says anything.
 */
internal fun auditInteractive(label: String, nodes: List<SemanticsNode>): Audit {
    var interactive = 0
    val unnamed = mutableListOf<String>()
    for (n in nodes) {
        val cfg = n.config
        val isInteractive = cfg.contains(SemanticsActions.OnClick) ||
            cfg.contains(SemanticsProperties.ToggleableState) ||
            cfg.contains(SemanticsActions.SetText)
        if (!isInteractive) continue
        interactive++
        if (textOf(cfg).isBlank()) {
            unnamed.add("$label#id=${n.id}:role=${cfg.getOrNull(SemanticsProperties.Role)}")
        }
    }
    return Audit(label, interactive, unnamed)
}

/**
 * Write a PNG into the app's own files dir and return its size in bytes (-1 on
 * failure). `adb pull` cannot read `/data/data`, so the gate script extracts these
 * with `run-as` + base64; a screenshot that never landed must not be reported as
 * evidence, hence the size in the verdict line.
 */
internal fun writeScreenshot(dir: File, name: String, capture: () -> ImageBitmap): Long {
    return runCatching {
        dir.mkdirs()
        val image = capture()
        // captureToImage() returns an ImageBitmap backed by an android.graphics.Bitmap,
        // so the conversion cannot fail on a device that rendered the frame at all.
        val bitmap = image.asAndroidBitmap()
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.length()
    }.onFailure {
        Log.w(GATE_TAG, "screenshot $name failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(-1L)
}

/**
 * Matches every node in the tree. `SemanticsMatcher`'s own companion does not
 * publish an "any" matcher, and the constructor is public, so this is built here
 * rather than relying on a constant whose availability varies by version.
 */
internal fun anyNodeMatcher(): SemanticsMatcher = SemanticsMatcher("any node") { true }

/** Matches any node whose accessible name contains [needle] (case-insensitive). */
internal fun textContainsMatcher(needle: String) = SemanticsMatcher("name contains '$needle'") { node ->
    textOf(node.config).contains(needle, ignoreCase = true)
}

/**
 * Matches nodes whose test tag starts with [prefix]. Compose 1.6 stores TestTag as
 * a list (a node can carry more than one), so the raw value is read as `Any?` and
 * both shapes are handled.
 */
internal fun tagPrefixMatcher(prefix: String) = SemanticsMatcher("TestTag starts with '$prefix'") { node ->
    when (val raw: Any? = node.config.getOrNull(SemanticsProperties.TestTag)) {
        is String -> raw.startsWith(prefix)
        is List<*> -> raw.any { (it as? String)?.startsWith(prefix) == true }
        else -> false
    }
}

/** A node is enabled unless it explicitly says otherwise. */
internal fun isEnabledNode(node: SemanticsNode): Boolean =
    node.config.getOrNull(SemanticsProperties.Enabled) != false
