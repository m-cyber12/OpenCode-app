package ai.opencode.android.ui.files

import ai.opencode.android.R
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Phase 10 continuation: the workspace, visible inside the app.
 *
 * Why this screen exists (the honest version): the agent writes real files, but
 * until Phase 10 they lived in app-private storage, which no file manager, no
 * MTP/USB browse and no non-root `adb shell` can see. Projects now live in the
 * app-specific EXTERNAL directory (resolved by the runtime layout class, never
 * by a screen), which is what makes
 * `adb pull` and a desktop file browser work — but on Android 11+ the platform
 * still blocks *other apps* from browsing `Android/data`, so a phone file manager
 * cannot open it either way. Two consequences, both handled here:
 *
 *  * this screen lists and opens the agent's files **through OpenCode's own file
 *    API** (`GET /file`, `GET /file/content`) — the same layer the agent's tools
 *    use, so what the user sees is what the agent sees, not a copy or a guess;
 *  * `Save a copy…` (SAF `CreateDocument`) and `Publish to a folder…` (SAF
 *    `OpenDocumentTree`) write real copies into a location the user picks, which
 *    any file manager can browse. That is the path to genuine external
 *    visibility on Android 11+ without `MANAGE_EXTERNAL_STORAGE`, which Play
 *    restricts to a narrow set of app types and which this app deliberately does
 *    not request.
 *
 * The screen is a pure function of the state it is handed (the static purity
 * check enforces that only AppRoot may reach the repository), so every branch is
 * covered by a Compose gate with fabricated state.
 */

/** One entry in a directory listing, already resolved for display. */
data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

/** A file opened for reading, or why it could not be. */
data class OpenFile(
    val path: String,
    val name: String,
    val text: String,
    val bytes: Long,
    val binary: Boolean,
    val truncated: Boolean,
)

@Composable
fun FilesScreen(
    projectName: String,
    projectPath: String,
    locationIsExternal: Boolean,
    currentPath: String,
    nodes: List<FileNode>,
    loading: Boolean,
    error: String,
    openFile: OpenFile?,
    publishLabel: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
    onCloseFile: () -> Unit,
    onCopyPath: (String) -> Unit,
    onSaveCopy: (String) -> Unit,
    onPublish: () -> Unit,
    onBack: () -> Unit,
) {
    val chat = ChatTheme.chat
    val where = if (currentPath.isEmpty()) "/" else "/$currentPath"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTag = "files_screen" },
    ) {
        AppTopBar(
            title = projectName,
            subtitle = stringResource(R.string.files_subtitle) + where,
            onBack = onBack,
        )

        // Where these files are on the device, said plainly. A user who wants to
        // reach them from outside the app deserves the exact path, not a
        // reassurance - including the part that is genuinely inconvenient.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { testTag = "files_location" },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.files_location_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = projectPath,
                    style = MonoSmall,
                    color = chat.muted,
                    modifier = Modifier.semantics { testTag = "files_location_path" },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (locationIsExternal) R.string.files_location_external else R.string.files_location_internal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                )
                if (publishLabel.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = publishLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.attention,
                        modifier = Modifier.semantics { testTag = "files_publish_label" },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onCopyPath(projectPath) },
                        modifier = Modifier.semantics { testTag = "files_copy_path" },
                    ) {
                        Text(stringResource(R.string.files_copy_path))
                    }
                    TextButton(
                        onClick = onPublish,
                        modifier = Modifier.semantics { testTag = "files_publish" },
                    ) {
                        Text(stringResource(R.string.files_publish))
                    }
                }
            }
        }

        // Either one file is open, or the folder is listed - never both, so the
        // screen has exactly one scrollable region at a time.
        val file = openFile
        if (file != null) {
            FileViewer(
                file = file,
                onClose = onCloseFile,
                onSaveCopy = { onSaveCopy(file.path) },
            )
        } else {
            Listing(
                currentPath = currentPath,
                nodes = nodes,
                loading = loading,
                error = error,
                onOpenDir = onOpenDir,
                onOpenFile = onOpenFile,
                onUp = onUp,
            )
        }
    }
}

/** The directory listing itself, kept separate so the viewer branch stays readable. */
@Composable
private fun Listing(
    currentPath: String,
    nodes: List<FileNode>,
    loading: Boolean,
    error: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
) {
    val chat = ChatTheme.chat
    Column(Modifier.fillMaxSize()) {
        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.files_loading), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error.isNotEmpty()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = chat.attention,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { testTag = "files_error" },
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTag = "files_list" },
        ) {
            if (currentPath.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUp)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics { testTag = "files_up" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.files_up),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.files_up), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (nodes.isEmpty() && !loading) {
                item {
                    Text(
                        text = stringResource(R.string.files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = chat.muted,
                        modifier = Modifier
                            .padding(16.dp)
                            .semantics { testTag = "files_empty" },
                    )
                }
            }
            items(nodes, key = { it.path }) { node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (node.isDirectory) onOpenDir(node.path) else onOpenFile(node.path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { testTag = if (node.isDirectory) "files_dir" else "files_file" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A folder gets the "opens into something" affordance; a file
                    // row is just its name (an icon that says nothing would be
                    // noise). Both carry an accessible name either way.
                    if (node.isDirectory) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.files_dir_description),
                            tint = chat.attention,
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileViewer(
    file: OpenFile,
    onClose: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    val chat = ChatTheme.chat
    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTag = "files_viewer" },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.semantics { testTag = "files_viewer_close" },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.files_viewer_close),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.files_viewer_size, file.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                    )
                }
                TextButton(
                    onClick = onSaveCopy,
                    modifier = Modifier.semantics { testTag = "files_save_copy" },
                ) {
                    Text(stringResource(R.string.files_save_copy))
                }
            }
            if (file.binary) {
                Text(
                    text = stringResource(R.string.files_viewer_binary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = chat.muted,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { testTag = "files_viewer_binary" },
                )
            } else {
                if (file.truncated) {
                    Text(
                        text = stringResource(R.string.files_viewer_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.attention,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { testTag = "files_viewer_body" },
                ) {
                    Text(
                        text = file.text,
                        style = MonoSmall,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}
