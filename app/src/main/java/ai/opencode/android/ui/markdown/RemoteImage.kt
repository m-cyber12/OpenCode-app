package ai.opencode.android.ui.markdown

import ai.opencode.android.R
import ai.opencode.android.web.WebImages
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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

/**
 * A website picture in the chat (2026-11-23). No card and no spinner: the picture is
 * drawn inline, full width, with rounded corners. It loads in the background through
 * [WebImages]; while it loads nothing is shown, and if it cannot load the alt text is
 * shown as plain muted text.
 */
@Composable
fun RemoteImage(url: String, alt: String, modifier: Modifier = Modifier) {
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
            Image(
                bitmap = image,
                contentDescription = description,
                contentScale = ContentScale.FillWidth,
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { testTag = "chat_image" },
            )
        }
        PictureState.Loading -> Unit
        PictureState.Failed -> Text(
            text = alt.ifBlank { description },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.semantics { testTag = "chat_image_failed" },
        )
    }
}
