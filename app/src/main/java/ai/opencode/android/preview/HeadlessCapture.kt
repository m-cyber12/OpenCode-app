package ai.opencode.android.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Same rendering contract as the visible pane (Chrome-default layout, JS on,
 * no cache - see PreviewPane), so a headless shot shows what the user WOULD
 * see on the Sandbox tab. Must be called from the main thread (WebView rule);
 * one WebView per request, destroyed on completion, finite by construction
 * (one load, one delayed snap).
 */
class HeadlessCapture(private val context: Context) {

    /**
     * Load [url] off-screen at [width]x[height] and hand back the settled
     * frame (null when the load or the draw failed). [onDone] arrives on the
     * main thread.
     */
    fun capture(url: String, width: Int, height: Int, settleMs: Long = 800, onDone: (Bitmap?) -> Unit) {
        if (width <= 0 || height <= 0) {
            onDone(null)
            return
        }
        val web = WebView(context)
        var finished = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        web.layout(0, 0, width, height)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loaded: String?) {
                if (finished) return
                finished = true
                view.postDelayed({
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
                    onDone(bmp)
                }, settleMs)
            }
        }
        // A page that never finishes must not leak the WebView: hard stop.
        web.postDelayed({
            if (!finished) {
                finished = true
                runCatching { web.destroy() }
                onDone(null)
            }
        }, TIMEOUT_MS)
        web.loadUrl(url)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
