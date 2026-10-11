package ai.opencode.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ai.opencode.android.R
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

/**
 * v9.35 (owner): the live preview is a chat surface.
 *
 *  * The console is a CLONE of the TODO panel's rounded-rectangle container
 *    (same surface, radius, border, elevation, padding), directly below it,
 *    with the server name inside. With no preview open it is the port door.
 *  * Opening plays the owner's morph: the rounded rectangle itself collapses
 *    into a circle at ITS OWN position (like the status orb), then the circle
 *    expands into the full-screen preview. Exit is the same sequence in
 *    reverse. No separate circle ever appears mid-screen - the morph shape
 *    starts at the measured console bounds.
 *
 * Every step is a finite tween; the WebView recipe (no cache, console mirror,
 * one deferred self-snapshot per load, web-history-aware back) carries over.
 */

/** The overlay's morph phase machine, owned by AppRoot. */
enum class PreviewState { CLOSED, OPENING, OPEN, CLOSING }

/** Everything the chat screen needs to render console + overlay. */
data class PreviewUi(
    val phase: PreviewState = PreviewState.CLOSED,
    val url: String = "",
    val port: Int = 0,
    val reloadStamp: Long = 0L,
    val reloadedFlash: Boolean = false,
)

/**
 * The console under the TODO panel. [modifier] lets ChatScreen measure the
 * container's bounds (the morph origin) and hide it while the overlay owns
 * the screen.
 */
@Composable
fun LivePreviewConsole(
    preview: PreviewUi,
    livePorts: List<Int>,
    onOpenPort: (Int) -> Unit,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(modifier = modifier) {
        if (preview.url.isNotEmpty()) {
            // The live bar: the TODO container, cloned, with the server name
            // inside. Tapping it re-opens the preview with the morph.
            val serverName = stringResource(R.string.sandbox_port_row, preview.port)
            val reloaded = stringResource(R.string.sandbox_console_reloaded)
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onShow() }
                    .semantics { testTag = "sandbox_console_bar" },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Box(Modifier.size(8.dp).background(chat.success, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    // The "Reloaded" word arrives and leaves with a finite
                    // Crossfade - the owner's reload acknowledgement.
                    Crossfade(targetState = preview.reloadedFlash, label = "console_line") { flash ->
                        Text(
                            text = if (flash) reloaded else serverName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (flash) chat.success else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = chat.muted,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        } else if (livePorts.isNotEmpty()) {
            // The door: same container, one clickable row per live port.
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { testTag = "sandbox_preview_open" },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(chat.muted, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.sandbox_preview_open),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    for (port in livePorts) {
                        val rowLabel = stringResource(R.string.sandbox_port_row, port)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPort(port) }
                                .semantics {
                                    testTag = "sandbox_port_$port"
                                    contentDescription = rowLabel
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(rowLabel, style = MonoSmall, modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The full-screen morph overlay. [origin] is the console's bounds in this
 * overlay's coordinate space - the shape the morph grows out of and returns
 * to. Progress 0 = the rounded rectangle, ~0.45 = the circle at the console
 * position, 1 = the preview page.
 */
@Composable
fun ChatPreviewOverlay(
    preview: PreviewUi,
    origin: Rect,
    onClose: () -> Unit,
    onBrowser: () -> Unit,
    onReload: () -> Unit,
    onCapture: (android.graphics.Bitmap) -> Unit,
    onConsole: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    val density = LocalDensity.current
    val latestCapture = remember { mutableStateOf(onCapture) }
    latestCapture.value = onCapture
    val latestConsole = remember { mutableStateOf(onConsole) }
    latestConsole.value = onConsole
    val webRef = remember { mutableStateOf<WebView?>(null) }
    // Back walks the page's own history first; only an empty history leaves
    // the preview - morphing back to the console, which stays live.
    BackHandler(preview.phase != PreviewState.CLOSED) {
        val web = webRef.value
        if (web != null && web.canGoBack()) web.goBack() else onClose()
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(preview.phase) {
        when (preview.phase) {
            PreviewState.OPENING -> progress.animateTo(1f, tween(460))
            PreviewState.CLOSING -> progress.animateTo(0f, tween(460))
            PreviewState.OPEN -> progress.snapTo(1f)
            PreviewState.CLOSED -> progress.snapTo(0f)
        }
    }
    val p = progress.value
    // Stage 1 (0..0.45): the rounded rectangle collapses into the circle at
    // its own centre. Stage 2 (0.45..1): the circle expands into the page.
    val t1 = (p / 0.45f).coerceIn(0f, 1f)
    val t2 = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val cardCornerPx = with(density) { 16.dp.toPx() }
    val circleD = if (origin.height > 0f) origin.height else with(density) { 56.dp.toPx() }
    val circleRect = Rect(
        left = origin.centerX - circleD / 2f,
        top = origin.centerY - circleD / 2f,
        right = origin.centerX + circleD / 2f,
        bottom = origin.centerY + circleD / 2f,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = Rect(
            0f,
            0f,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
        )
        val startRect = if (origin.width > 0f) origin else circleRect
        val midRect = lerpRect(startRect, circleRect, t1)
        val morphRect = lerpRect(midRect, full, t2)
        val cornerPx = (cardCornerPx + (circleD / 2f - cardCornerPx) * t1) * (1f - t2)
        val cornerDp = with(density) { cornerPx.toDp() }

        // The page layer appears while the circle expands, and fades back
        // while the circle collapses on exit.
        if (p > 0.5f) {
            Column(
                Modifier
                    .fillMaxSize()
                    .alpha(((t2 - 0.1f) / 0.9f).coerceIn(0f, 1f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "sandbox_preview_close" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sandbox_preview_close),
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
                        )
                    }
                    IconButton(
                        onClick = onReload,
                        modifier = Modifier.semantics {
                            testTag = "sandbox_preview_reload"
                            contentDescription = stringResource(R.string.sandbox_console_reload)
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
                        WebPage(
                            url = preview.url,
                            webRef = webRef,
                            onCapture = { bmp -> latestCapture.value(bmp) },
                            onConsole = { line -> latestConsole.value(line) },
                        )
                    }
                }
            }
        }

        // The morph shape: the console container itself, collapsing into the
        // circle and expanding into the page (the same sequence in reverse on
        // exit). Hidden once the page fully owns the screen.
        if (p < 0.999f && morphRect.width > 0f && morphRect.height > 0f) {
            val shapeColor = lerp(chat.toolContainer, chat.goldBright, t1)
            Box(
                Modifier
                    .offset {
                        IntOffset(morphRect.left.roundToInt(), morphRect.top.roundToInt())
                    }
                    .then(
                        with(density) {
                            Modifier
                                .width(morphRect.width.toDp())
                                .height(morphRect.height.toDp())
                        },
                    )
                    .clip(RoundedCornerShape(cornerDp))
                    .background(shapeColor),
            ) {
                // Ghost of the bar's row, so the container visibly folds into
                // itself; gone by the time the circle forms.
                if (t1 < 1f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .alpha(1f - t1)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(chat.success, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.sandbox_port_row, preview.port),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The rendered page - the v9.18 WebView recipe, carried over from the old
 * Sandbox pane: FrameLayout host, no cache, console mirrored to the project,
 * one deferred self-snapshot per settled load, load only at real size.
 */
@Composable
private fun WebPage(
    url: String,
    webRef: androidx.compose.runtime.MutableState<WebView?>,
    onCapture: (android.graphics.Bitmap) -> Unit,
    onConsole: (String) -> Unit,
) {
    val latestCapture = remember { mutableStateOf(onCapture) }
    latestCapture.value = onCapture
    val latestConsole = remember { mutableStateOf(onConsole) }
    latestConsole.value = onConsole
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
    AndroidViewHost(
        factory = { ctx ->
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
                        val engine = runCatching {
                            WebView.getCurrentWebViewPackage()
                                ?.let { "${it.packageName} ${it.versionName}" }
                        }.getOrNull() ?: "unknown"
                        runCatching { latestConsole.value("LOADED ${finished ?: ""} [webview: $engine]") }
                        runCatching { view.scrollTo(0, 0) }
                        // One deferred self-snapshot per load: the page
                        // settles, the frame lands in the project, the agent
                        // can read what is on screen. A single delayed
                        // runnable - finite by construction.
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
                        (v as WebView).loadUrl(url)
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
        onRelease = { host ->
            val w = host.getChildAt(0) as? WebView
            runCatching { host.removeAllViews() }
            runCatching { w?.destroy() }
            webRef.value = null
        },
    )
}

/** Component-wise rect interpolation, so the morph needs no extra API. */
private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left = a.left + (b.left - a.left) * t,
    top = a.top + (b.top - a.top) * t,
    right = a.right + (b.right - a.right) * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)

/** The FrameLayout-hosted AndroidView, clipped on both sides. */
@Composable
private fun AndroidViewHost(
    factory: (android.content.Context) -> android.widget.FrameLayout,
    onRelease: (android.widget.FrameLayout) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = factory,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        onRelease = onRelease,
    )
}
