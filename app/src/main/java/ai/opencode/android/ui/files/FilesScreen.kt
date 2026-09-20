package ai.opencode.android.ui.files

import ai.opencode.android.R
import ai.opencode.android.runtime.StorageMode
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
 * Phase 10 continuation v3: the workspace, visible inside the app - and the
 * panel that decides where it lives.
 *
 * Why this screen exists (the honest version). The agent writes real files. Until
 * Phase 10 they lived in app-private storage, which no file manager, no MTP/USB
 * browse and no non-root `adb shell` can see; the first continuation moved them
 * to the app-specific EXTERNAL directory, which `adb` and a desktop browser can
 * reach but file managers on Android 11+ still cannot, because the platform
 * blocks apps from browsing `Android/data`. The v3 answer is the one the user
 * asked for: **projects live in a normal shared folder**
 * (`Documents/OpenCode/<project>`), visible to the Files app, any file manager,
 * MTP and `adb` from the moment they are created, with no export step.
 *
 * Straight talk about the price of that: writing there needs All files access
 * (`MANAGE_EXTERNAL_STORAGE`), which is a special permission the user grants from
 * system settings - not a dialog. So this screen carries a storage panel that
 *
 *  * states where the projects actually are, in the app's own words, per mode
 *    (the mode arrives as data; this screen decides nothing about storage) - including the case where the
 *    permission was refused and the files are therefore back in `Android/data`,
 *    where a phone file manager can no longer open them;
 *  * offers the three real fixes: grant All files access, pick a folder with the
 *    system picker (Storage Access Framework), or go back to the default;
 *  * moves the projects the user already has into the new location, reporting the
 *    count, and never deleting anything it could not move ([ProjectMigration]);
 *  * keeps `Export a copy...` (a full tree copy through `OpenDocumentTree`) for
 *    the case where the user wants a snapshot somewhere else entirely - it is a
 *    convenience now, not the only route out.
 *
 * And the part that did not change: the listing and the viewer read the files
 * **through OpenCode's own file API** (`GET /file`, `GET /file/content`) - the
 * same layer the agent's tools use, so what the user sees is what the agent sees,
 * not a copy or a guess.
 *
 * The screen is a pure function of the state it is handed (the static purity
 * check enforces that only AppRoot may reach the repository), so every branch is
 * covered by a Compose gate with fabricated state; the storage facts come in as
 * plain values resolved by AppRoot from the runtime layout class (which this
 * layer may not even import - the purity check enforces that).
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
    /** Which of the storage locations the projects are in. Drives the panel text. */
    storageMode: StorageMode,
    /** True when a file manager (not just `adb`) can open the live project folder. */
    storageVisibleToFileManagers: Boolean,
    /** True when a file manager can browse `Android/data` on this Android version. */
    storageAppFolderBrowsableByFileManagers: Boolean,
    /** True when the All-files-access grant is offered and not yet held. */
    storageCanGrantAllFilesAccess: Boolean,
    /** True when the system folder picker can be offered (always, in practice). */
    storageCanChooseFolder: Boolean,
    /** True when a folder the user chose is in effect (offers "use the default"). */
    storageHasChosenFolder: Boolean,
    /** Projects still sitting in an older location, waiting to be moved (0 = none). */
    storagePendingMove: Int,
    /** Result of the last storage action, or "" - shown so a move is never silent. */
    storageMessage: String,
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
    onRequestAllFilesAccess: () -> Unit,
    onChooseStorageFolder: () -> Unit,
    onUseDefaultStorage: () -> Unit,
    onMoveProjects: () -> Unit,
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

        // Where these files are on the device, said plainly - and what can be
        // done about it when the answer is "somewhere a file manager cannot open".
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
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(storageModeLabel(storageMode)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { testTag = "files_storage_mode" },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = storageExplanation(
                        mode = storageMode,
                        visibleToFileManagers = storageVisibleToFileManagers,
                        appFolderBrowsable = storageAppFolderBrowsableByFileManagers,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                    modifier = Modifier.semantics { testTag = "files_storage_explanation" },
                )
                if (storagePendingMove > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.files_storage_pending, storagePendingMove),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.attention,
                        modifier = Modifier.semantics { testTag = "files_storage_pending" },
                    )
                }
                if (!storageVisibleToFileManagers && (storageCanGrantAllFilesAccess || storageCanChooseFolder)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.files_storage_make_visible_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
                    )
                }
                if (publishLabel.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = publishLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.attention,
                        modifier = Modifier.semantics { testTag = "files_publish_label" },
                    )
                }
                if (storageMessage.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = storageMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.attention,
                        modifier = Modifier.semantics { testTag = "files_storage_message" },
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
                        Text(stringResource(R.string.files_export_copy))
                    }
                }
                if (storageCanGrantAllFilesAccess || storageCanChooseFolder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (storageCanGrantAllFilesAccess) {
                            TextButton(
                                onClick = onRequestAllFilesAccess,
                                modifier = Modifier.semantics { testTag = "files_storage_grant" },
                            ) {
                                Text(stringResource(R.string.files_storage_make_visible))
                            }
                        }
                        if (storageCanChooseFolder) {
                            TextButton(
                                onClick = onChooseStorageFolder,
                                modifier = Modifier.semantics { testTag = "files_storage_choose" },
                            ) {
                                Text(stringResource(R.string.files_storage_choose_folder))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.files_storage_choose_folder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
                    )
                }
                if (storagePendingMove > 0 || storageHasChosenFolder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (storagePendingMove > 0) {
                            TextButton(
                                onClick = onMoveProjects,
                                modifier = Modifier.semantics { testTag = "files_storage_move" },
                            ) {
                                Text(stringResource(R.string.files_storage_move_action))
                            }
                        }
                        if (storageHasChosenFolder) {
                            TextButton(
                                onClick = onUseDefaultStorage,
                                modifier = Modifier.semantics { testTag = "files_storage_default" },
                            ) {
                                Text(stringResource(R.string.files_storage_choose_reset))
                            }
                        }
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

/** The one-line name of the active storage location, per mode. */
private fun storageModeLabel(mode: StorageMode): Int = when (mode) {
    StorageMode.PUBLIC -> R.string.files_storage_mode_public
    StorageMode.CHOSEN -> R.string.files_storage_mode_chosen
    StorageMode.APP_EXTERNAL -> R.string.files_storage_mode_app_external
    StorageMode.INTERNAL -> R.string.files_storage_mode_internal
}

/**
 * What the user needs to know about the current location, in the app's own words.
 *
 * No reassurance is invented here: each branch says what the location is visible
 * to on THIS device, using values AppRoot resolved from the platform, and says
 * what to do when the answer is not good enough. [visibleToFileManagers] is part
 * of the contract even where the mode already implies it, so the caller cannot
 * pass a mode and a verdict that contradict each other without that showing up in
 * the gate that renders this function.
 */
@Composable
private fun storageExplanation(
    mode: StorageMode,
    visibleToFileManagers: Boolean,
    appFolderBrowsable: Boolean,
): String = if (!visibleToFileManagers && mode == StorageMode.APP_EXTERNAL) {
    stringResource(R.string.files_location_app_external)
} else when (mode) {
    StorageMode.PUBLIC -> stringResource(R.string.files_location_public)
    StorageMode.CHOSEN -> stringResource(R.string.files_location_chosen)
    StorageMode.APP_EXTERNAL -> stringResource(
        if (appFolderBrowsable) {
            R.string.files_location_app_external_android10
        } else {
            R.string.files_location_app_external
        },
    )
    StorageMode.INTERNAL -> stringResource(R.string.files_location_internal)
}
