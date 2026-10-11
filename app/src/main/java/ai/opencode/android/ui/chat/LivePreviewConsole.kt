package ai.opencode.android.ui.chat

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.opencode.android.R
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall

/**
 * v9.35 (owner): the live preview moved OUT of the Sandbox tab and INTO the
 * chat screen, as a console directly below the TODO panel.
 *
 *  * The console names the port (127.0.0.1:PORT), offers Reload and Close,
 *    and while no preview is live it is the door: one row per port the agent
 *    opened (same gating as before - no live port, no door).
 *  * The preview itself is a full-screen overlay on the chat with the morph
 *    the owner described: the console state collapses into a small circle,
 *    the circle expands into the rendered page; closing plays the sequence
 *    in reverse. All of it is FINITE - plain enter/exit transitions on two
 *    layers (circle badge, web view), never an infinite animation.
 *  * Opening happens automatically only on the FIRST port open of the app's
 *    life; later server starts / app reloads show an animated "Reloaded"
 *    chip in the console instead (AppRoot owns that decision).
 */

/** The overlay's morph phases - a plain state machine, driven by AppRoot. */
object PreviewState {
    const val CLOSED = 0
    const val OPENING = 1
    const val OPEN = 2
    const val CLOSING = 3
}

/** Everything the chat needs to render the console and the overlay. */
data class PreviewUi(
    val phase: Int = PreviewState.CLOSED,
    val url: String = "",
    val port: Int = 0,
    /** Bumped on every reload so the WebView is reborn with fresh bytes. */
    val reloadStamp: Long = 0L,
    /** True while the "Reloaded" chip animates in the console. */
    val reloadedFlash: Boolean = false,
)

/**
 * The console card under the TODO panel. Three faces:
 *  - preview live  : compact row - port, url, Reload, Close;
 *  - ports live    : the door, one tappable row per port;
 *  - reloaded      : the animated "Reloaded" chip (with or without a door).
 */
@Composable
fun LivePreviewConsole(
    preview: PreviewUi,
    livePorts: List<Int>,
    onOpenPort: (Int) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    onBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    val reloadDesc = stringResource(R.string.sandbox_console_reload)
    Column(modifier = modifier.fillMaxWidth()) {
        if (preview.phase != PreviewState.CLOSED) {
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { testTag = "sandbox_preview_open" },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(chat.success, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.sandbox_console_title,
                                preview.port,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = preview.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onBrowser,
                        modifier = Modifier.semantics { testTag = "sandbox_open_browser" },
                    ) {
                        Text(
                            stringResource(R.string.sandbox_open_browser),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(
                        onClick = onReload,
                        modifier = Modifier.semantics {
                            testTag = "sandbox_preview_reload"
                            contentDescription = reloadDesc
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.sandbox_console_reload),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "sandbox_preview_close" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sandbox_console_close),
                        )
                    }
                }
            }
        } else {
            // v9.35 (owner): after the first auto-open, later server starts
            // and app reloads must NOT grab the screen - the chip says what
            // happened, animates in, and fades back to the chat. FINITE.
            AnimatedVisibility(
                visible = preview.reloadedFlash,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f),
                exit = fadeOut(tween(320)),
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { testTag = "sandbox_reloaded" },
            ) {
                Surface(
                    color = chat.toolContainer,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, chat.toolBorder),
                ) {
                    Text(
                        text = stringResource(R.string.sandbox_console_reloaded),
                        style = MaterialTheme.typography.labelMedium,
                        color = chat.muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (livePorts.isNotEmpty()) {
                Surface(
                    color = chat.toolContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, chat.toolBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .semantics { testTag = "sandbox_preview_open" },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.sandbox_preview_open),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.sandbox_ports_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = chat.muted,
                        )
                        for (port in livePorts) {
                            val rowLabel = stringResource(R.string.sandbox_port_row, port)
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                onClick = { onOpenPort(port) },
                                color = chat.toolContainer,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, chat.toolBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        testTag = "sandbox_port_${port}"
                                        contentDescription = rowLabel
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        rowLabel,
                                        style = MonoSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        stringResource(R.string.sandbox_console_show),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = chat.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The full-screen morph overlay, composed LAST inside the chat's root Box so
 * it covers header, transcript, composer and tabs alike.
 *
 * Sequence (all finite tweens): OPENING = the circle badge scales up from
 * nothing; then the web view fades in over it while the badge scales away -
 * the circle "becomes" the page. CLOSING reverses it: page fades out, the
 * circle returns and shrinks back to nothing.
 */
@Composable
fun ChatPreviewOverlay(
    preview: PreviewUi,
    onClose: () -> Unit,
    onBrowser: () -> Unit,
    onReload: () -> Unit,
    onCapture: (android.graphics.Bitmap) -> Unit,
    onConsole: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    val reloadDesc = stringResource(R.string.sandbox_console_reload)
    val latestCapture = rememberUpdatedState(onCapture)
    val latestConsole = rememberUpdatedState(onConsole)
    val webRef = remember { mutableStateOf<WebView?>(null) }
    // A preview is a browser: the back key walks the page history first and
    // only leaves the preview once the history is empty.
    BackHandler(preview.phase != PreviewState.CLOSED) {
        val web = webRef.value
        if (preview.phase == PreviewState.OPEN && web != null && web.canGoBack()) {
            web.goBack()
        } else if (preview.phase == PreviewState.OPEN) {
            onClose()
        }
    }
    fun snap(web: WebView?) {
        val w = web ?: return
        if (w.width > 0 && w.height > 0) {
            val bmp = android.graphics.Bitmap.createBitmap(
                w.width,
                w.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            w.draw(android.graphics.Canvas(bmp))
            latestCapture.value(bmp)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xF20B0A08))
            .semantics { testTag = "sandbox_overlay" },
        contentAlignment = Alignment.Center,
    ) {
        // The web view layer - visible only in OPEN; fades in as the circle
        // expands into it, fades out first on the way back.
        AnimatedVisibility(
            visible = preview.phase == PreviewState.OPEN,
            enter = fadeIn(tween(170)),
            exit = fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "sandbox_preview_close" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sandbox_console_close),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sandbox_console_title, preview.port),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            text = preview.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = chat.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onReload,
                        modifier = Modifier.semantics {
                            testTag = "sandbox_preview_reload"
                            contentDescription = reloadDesc
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.sandbox_console_reload),
                        )
                    }
                    TextButton(
                        onClick = onBrowser,
                        modifier = Modifier.semantics { testTag = "sandbox_open_browser" },
                    ) {
                        Text(stringResource(R.string.sandbox_open_browser))
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { testTag = "sandbox_preview" },
                ) {
                    key(preview.url, preview.reloadStamp) {
                        AndroidView(
                            factory = { ctx ->
                                // Same hardened WebView recipe the Sandbox pane
                                // used: clipped FrameLayout host, no stale cache,
                                // console mirrored for the agent, one deferred
                                // self-snapshot per settled load.
                                val web = WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                    settings.useWideViewPort = false
                                    settings.loadWithOverviewMode = false
                                    settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                                            runCatching {
                                                latestConsole.value(
                                                    "${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}",
                                                )
                                            }
                                            return true
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, finished: String?) {
                                            runCatching { view.scrollTo(0, 0) }
                                            runCatching {
                                                latestConsole.value("LOADED ${finished ?: ""}")
                                            }
                                            view.postDelayed({ runCatching { snap(view) } }, 600)
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: android.webkit.WebResourceRequest,
                                            error: android.webkit.WebResourceError,
                                        ) {
                                            runCatching {
                                                latestConsole.value(
                                                    "LOAD_ERROR ${request.url} code=${error.errorCode} ${error.description}",
                                                )
                                            }
                                        }
                                    }
                                    webRef.value = this
                                    var loadedOnce = false
                                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                        if (!loadedOnce && v.width > 0 && v.height > 0) {
                                            loadedOnce = true
                                            (v as WebView).loadUrl(preview.url)
                                        }
                                    }
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                                android.widget.FrameLayout(ctx).apply {
                                    clipChildren = true
                                    clipToPadding = true
                                    addView(
                                        web,
                                        android.widget.FrameLayout.LayoutParams(
                                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize().clipToBounds(),
                            onRelease = { host ->
                                val w = host.getChildAt(0) as? WebView
                                runCatching { host.removeAllViews() }
                                runCatching { w?.destroy() }
                            },
                        )
                    }
                }
            }
        }
        // The circle badge layer - the "small circle" of the morph. Scales up
        // while OPENING, away once the page takes over; back on CLOSING and
        // shrinking to nothing as the phase returns to CLOSED.
        AnimatedVisibility(
            visible = preview.phase == PreviewState.OPENING ||
                preview.phase == PreviewState.CLOSING,
            enter = fadeIn(tween(90)) + scaleIn(tween(170), initialScale = 0.2f),
            exit = scaleOut(tween(170), targetScale = 0.15f) + fadeOut(tween(150)),
        ) {
            Surface(
                color = chat.toolContainer,
                shape = CircleShape,
                border = BorderStroke(1.dp, chat.goldBright),
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (preview.port > 0) ":${preview.port}" else "",
                        style = MonoSmall,
                        color = chat.goldBright,
                    )
                }
            }
        }
    }
}
