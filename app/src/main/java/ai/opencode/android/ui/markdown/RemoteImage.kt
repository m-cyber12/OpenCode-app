package ai.opencode.android.ui.markdown

import ai.opencode.android.R
import ai.opencode.android.web.WebImages
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PictureState {
    object Loading : PictureState
    class Ready(val bitmap: android.graphics.Bitmap) : PictureState
    object Failed : PictureState
}

/**
 * A website picture in the chat, full width of its column (2026-11-23). Shows the
 * alt text if it cannot be loaded. Loading is done by [WebImages], outside the UI.
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
    Surface(
        modifier = modifier.fillMaxWidth().semantics { testTag = "chat_image" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        when (val s = state) {
            is PictureState.Ready -> Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            PictureState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            PictureState.Failed -> Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
