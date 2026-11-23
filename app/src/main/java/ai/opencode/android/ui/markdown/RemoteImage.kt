package ai.opencode.android.ui.markdown

import ai.opencode.android.R
import ai.opencode.android.web.WebImages
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PictureState {
    object Loading : PictureState
    class Ready(val bitmap: Bitmap) : PictureState
    object Failed : PictureState
}

/** Where and how big a website picture is drawn (2026-11-23, owner: "different sizes and locations"). */
enum class PictureStyle {
    /** Its own line, full width of the bubble. */
    Full,
    /** One tile of a grid of pictures; cropped to a fixed 4:3 frame. */
    Tile,
    /** A small logo at the start of a list item. */
    Logo,
}

/**
 * A website picture in the chat. No card and no spinner: the picture is drawn inline
 * with rounded corners. It loads in the background through [WebImages]; while it loads
 * a grid tile keeps its frame so the grid does not jump, and if it cannot load the alt
 * text is shown as plain muted text.
 */
@Composable
fun RemoteImage(
    url: String,
    alt: String,
    modifier: Modifier = Modifier,
    style: PictureStyle = PictureStyle.Full,
) {
    val state by produceState<PictureState>(
        initialValue = WebImages.cached(url)?.let { PictureState.Ready(it) } ?: PictureState.Loading,
        key1 = url,
    ) {
        if (value !is PictureState.Ready) {
            val bitmap = withContext(Dispatchers.IO) { WebImages.fetch(url) }
            value = if (bitmap != null) PictureState.Ready(bitmap) else PictureState.Failed
        }
    }
    val description = alt.ifBlank { stringResource(R.string.chat_image_default_description) }
    when (val s = state) {
        is PictureState.Ready -> {
            val image = remember(s.bitmap) { s.bitmap.asImageBitmap() }
            val shaped = when (style) {
                PictureStyle.Full -> modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                PictureStyle.Tile -> modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp))
                PictureStyle.Logo -> modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            }
            Image(
                bitmap = image,
                contentDescription = description,
                contentScale = when (style) {
                    PictureStyle.Full -> ContentScale.FillWidth
                    PictureStyle.Tile -> ContentScale.Crop
                    PictureStyle.Logo -> ContentScale.Fit
                },
                modifier = shaped.semantics { testTag = "chat_image" },
            )
        }
        PictureState.Loading -> if (style == PictureStyle.Tile) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            )
        }
        PictureState.Failed -> Text(
            text = alt.ifBlank { description },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.semantics { testTag = "chat_image_failed" },
        )
    }
}
