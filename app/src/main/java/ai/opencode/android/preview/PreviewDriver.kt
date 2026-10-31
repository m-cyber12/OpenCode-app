package ai.opencode.android.preview

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A batch waiting for the user's answer to the permission prompt. */
class PendingBatch(val batch: PreviewBatch, val decision: CompletableDeferred<Boolean>)

/**
 * Runs a [PreviewBatch] on the WebView of the open preview pane. The WebView
 * rule applies: call from the main thread only. Steps run in order and the
 * batch stops at the first failure; the rest are reported as skipped.
 */
object PreviewDriver {
    /** Pause after a tap, typing or a scroll, so the page can react before the next step. */
    private const val SETTLE_MS = 400L
    private const val EVAL_TIMEOUT_MS = 5_000L

    /** Returns the text report for [PreviewActions.RESULT_FILE]. */
    suspend fun runBatch(web: WebView?, batch: PreviewBatch, consoleErrors: () -> List<String>): String {
        if (web == null) {
            return "ACT ${batch.id} failed steps=0/${batch.steps.size} ms=0\n  the preview page is not ready; open the preview and try again"
        }
        val started = System.currentTimeMillis()
        val results = ArrayList<StepResult>()
        for (step in batch.steps) {
            val result: StepResult = when (step) {
                is PreviewStep.Tap -> {
                    val raw = evaluate(web, PreviewActions.tapScript(step.selector, step.text, step.x, step.y))
                    val r = PreviewActions.outcome(step, raw)
                    if (r.ok) delay(SETTLE_MS)
                    r
                }
                is PreviewStep.Type -> {
                    val raw = evaluate(web, PreviewActions.typeScript(step.selector, step.value))
                    val r = PreviewActions.outcome(step, raw)
                    if (r.ok) delay(SETTLE_MS)
                    r
                }
                is PreviewStep.Scroll -> {
                    val raw = evaluate(web, PreviewActions.scrollScript(step.y))
                    val r = PreviewActions.outcome(step, raw)
                    if (r.ok) delay(SETTLE_MS)
                    r
                }
                is PreviewStep.Wait -> {
                    delay(step.ms)
                    StepResult(step.label, true, "")
                }
                is PreviewStep.Snapshot -> PreviewActions.outcome(step, evaluate(web, PreviewActions.SNAPSHOT_SCRIPT))
            }
            results += result
            if (!result.ok) break
        }
        val ms = System.currentTimeMillis() - started
        return PreviewActions.digest(batch, results, ms, consoleErrors())
    }

    /** One script on the page. Null when the page does not answer in time or the script returned nothing. */
    private suspend fun evaluate(web: WebView, script: String): String? =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                runCatching {
                    web.evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw) }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
}
