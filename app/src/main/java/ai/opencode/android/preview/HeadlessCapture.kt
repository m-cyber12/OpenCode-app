package ai.opencode.android.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * v9.12 (owner): "the model should be able to take screenshots at any number
 * of important stages" - including stages where the user is on the Chat tab
 * and no preview pane is composed at all. The visible pane can only capture
 * what is on screen; this engine captures regardless: an OFF-SCREEN WebView
 * laid out at device size loads the page, settles, is drawn into a bitmap,
 * and is destroyed.
 *
 * v9.14 (owner's FocusList run proved the v9.12 version was a silent no-op):
 * the settle delay and the timeout used View.postDelayed - but runnables
 * posted on a view that is NEVER ATTACHED to a window stay queued until
 * attachment, i.e. forever here. Nothing ran, nothing was saved, nothing was
 * logged. Both delays now go through a main-looper Handler, which runs
 * regardless of attachment, and every outcome is reported with a reason so
 * the capture pipeline can never fail silently again.
 *
 * Same rendering contract as the visible pane (Chrome-default layout, JS on,
 * no cache - see PreviewPane). Must be called from the main thread (WebView
 * rule); one WebView per request, destroyed on completion, finite by
 * construction (one load, one Handler-delayed snap, one Handler timeout).
 */
class HeadlessCapture(private val context: Context) {

    /**
     * Load [url] off-screen at [width]x[height] and hand back the settled
     * frame plus a reason string ("ok", "timeout", "draw-failed",
     * "bad-size"). [onDone] arrives on the main thread; bitmap is null
     * exactly when the reason is not "ok".
     */
    fun capture(url: String, width: Int, height: Int, settleMs: Long = 800, onDone: (Bitmap?, String) -> Unit) {
        if (width <= 0 || height <= 0) {
            onDone(null, "bad-size")
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val web = WebView(context)
        var finished = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Never attached to a window -> no hardware layer exists; force the
        // software draw path explicitly so draw() renders instead of
        // depending on an attachment-time decision.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        web.layout(0, 0, width, height)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loaded: String?) {
                if (finished) return
                finished = true
                // v9.14: Handler, NOT view.postDelayed - see class comment.
                handler.postDelayed({
                    val bmp = runCatching {
                        // Re-layout after the page settled, then draw.
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, width, height)
                        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(target))
                        target
                    }.getOrNull()
                    runCatching { view.destroy() }
                    onDone(bmp, if (bmp != null) "ok" else "draw-failed")
                }, settleMs)
            }
        }
        // A page that never finishes must not leak the WebView: hard stop.
        handler.postDelayed({
            if (!finished) {
                finished = true
                runCatching { web.destroy() }
                onDone(null, "timeout")
            }
        }, TIMEOUT_MS)
        web.loadUrl(url)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
