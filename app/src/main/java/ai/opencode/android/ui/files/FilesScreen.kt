package ai.opencode.android.ui.files

import ai.opencode.android.R
import ai.opencode.android.runtime.StorageMode
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.ArchiveGlyph
import ai.opencode.android.ui.common.AudioGlyph
import ai.opencode.android.ui.common.CloseGlyph
import ai.opencode.android.ui.common.CodeGlyph
import ai.opencode.android.ui.common.DocGlyph
import ai.opencode.android.ui.common.FolderGlyph
import ai.opencode.android.ui.common.ImageGlyph
import ai.opencode.android.ui.common.PdfGlyph
import ai.opencode.android.ui.common.QuestionGlyph
import ai.opencode.android.ui.common.SheetGlyph
import ai.opencode.android.ui.common.VideoGlyph
import ai.opencode.android.ui.common.WebGlyph
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.ProjectTabs
import ai.opencode.android.ui.markdown.MarkdownText
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.activity.compose.BackHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
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
    /** v9.38 (owner): a file-manager row shows its facts - byte size (-1 =
     *  unknown/folder) and the modification time (0 = unknown). AppRoot's
     *  listing fills both from one stat pass; fabricated states may omit. */
    val bytes: Long = -1L,
    val modifiedMs: Long = 0L,
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

@OptIn(ExperimentalMaterial3Api::class)
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
    /**
     * True when the All-files-access grant is offered and not yet held. Kept after
     * the v4 simplification on purpose: the workspace folder is now chosen in
     * Settings, so this screen has no picker, but a user whose grant was revoked in
     * system settings still needs a way back to the setting that fixes it - the
     * alternative is a screen that says "a file manager cannot open this" with no
     * way to change that.
     */
    storageCanGrantAllFilesAccess: Boolean,
    /** Projects still sitting in an older location, waiting to be moved (0 = none). */
    storagePendingMove: Int,
    /** Result of the last storage action, or "" - shown so a move is never silent. */
    storageMessage: String,
    currentPath: String,
    nodes: List<FileNode>,
    loading: Boolean,
    error: String,
    openFile: OpenFile?,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
    onCloseFile: () -> Unit,
    onSaveCopy: (String) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onMoveProjects: () -> Unit,
    onBack: () -> Unit,
    /**
     * v9.9 (owner): the door only exists while something actually answers on
     * a loopback port. The app's PortScanner feeds this; empty = no door at
     * all. v9.35 (owner): tapping a port now walks the user to the CHAT,
     * where the preview console and the morph overlay live.
     */
    livePorts: List<Int> = emptyList(),
    onOpenPort: (Int) -> Unit = {},
    /**
     * v7 redesign: when non-null, the Chat/Files/Changes/Terminal strip renders
     * under the bar and this callback handles a tab tap. Nullable so every
     * existing call site (and the U9 gate's fixture) is untouched.
     */
    onSelectTab: ((ProjectTab) -> Unit)? = null,
) {
    val chat = ChatTheme.chat
    val where = if (currentPath.isEmpty()) "/" else "/$currentPath"
    // v9.38 (owner): the top-of-screen explanation is not a permanent fixture
    // any more - "move all the explanatory text into a small circular button
    // with a question-mark icon, which users can tap to view the information
    // when needed." The dot in the path chip stays: an UNANSWERED question
    // (a missing grant, projects waiting to move) must not hide behind the
    // very "?" it is asking about.
    var showInfo by remember { mutableStateOf(false) }
    val infoLabel = stringResource(R.string.files_info)
    BackHandler(enabled = showInfo) { showInfo = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTag = "files_screen" },
    ) {
      Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = projectName,
            subtitle = stringResource(R.string.files_subtitle) + where,
            onBack = onBack,
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).semantics { testTag = "files_location" },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = FolderGlyph,
                        contentDescription = null,
                        tint = chat.attention,
                        modifier = Modifier.size(15.dp).clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = where,
                        style = MonoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (storagePendingMove > 0 || (!storageVisibleToFileManagers && storageCanGrantAllFilesAccess)) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(7.dp)
                                .background(chat.attention, CircleShape)
                                .semantics { testTag = "files_storage_needs_action" },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // The tag sits DIRECTLY on the IconButton's modifier - the shape
            // every clickable the gates drive uses (files_viewer_close,
            // sandbox_preview_close, the door rows). Style inside the content
            // so nothing can interpose between the click semantics and the
            // node the test finds.
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier
                    .size(34.dp)
                    .semantics {
                        testTag = "files_info"
                        contentDescription = infoLabel
                    },
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(chat.toolContainer)
                        .border(1.dp, chat.toolBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QuestionGlyph,
                        contentDescription = null,
                        tint = chat.muted,
                        modifier = Modifier.size(17.dp).clearAndSetSemantics { },
                    )
                }
            }
        }

        // v9.9 (owner): the door to the live preview exists ONLY while a
        // server is actually answering on the loopback - the agent opening
        // any watched port is what makes the button appear. One row per live
        // port; no port, no door.
        if (openFile == null && livePorts.isNotEmpty()) {
            val chat = ChatTheme.chat
            val openLabel = stringResource(R.string.sandbox_preview_open)
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
                    Text(openLabel, style = MaterialTheme.typography.labelLarge)
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

        // Either one file is open, or the folder is listed - never both, so the
        // screen has exactly one scrollable region at a time. v9.35: the
        // preview pane itself moved into the chat screen.
        val file = openFile
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (file != null) {
                FileViewer(
                    file = file,
                    projectPath = projectPath,
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
        // v8: the surfaces live in a bottom bar now - same tags as the v7 strip.
        if (onSelectTab != null && openFile == null) {
            ProjectTabs(current = ProjectTab.FILES, onSelect = onSelectTab)
        }
      }
      if (showInfo && openFile == null) {
        // v9.38 (owner): all the explanation, one tap away - where it was
        // always meant to be. The actions it carried (grant, move) live here
        // too; the dot on the path chip is what draws the eye to them.
        FilesInfoSheet(
            projectPath = projectPath,
            storageMode = storageMode,
            storageVisibleToFileManagers = storageVisibleToFileManagers,
            storageAppFolderBrowsableByFileManagers = storageAppFolderBrowsableByFileManagers,
            storageCanGrantAllFilesAccess = storageCanGrantAllFilesAccess,
            storagePendingMove = storagePendingMove,
            storageMessage = storageMessage,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onMoveProjects = onMoveProjects,
            onClose = { showInfo = false },
        )
      }
    }
}

/**
 * v9.38 (owner): everything the old top panel explained, behind the "?" on
 * the listing - location, mode, what a file manager can open here, and the
 * two actions that only this screen offers. The scrim CONSUMES taps (the
 * v9.38 overlay lesson: a Compose background alone lets touches fall through
 * to the rows underneath), and both ways out - the scrim and the close key -
 * land on the same quiet state change.
 */
@Composable
private fun FilesInfoSheet(
    projectPath: String,
    storageMode: StorageMode,
    storageVisibleToFileManagers: Boolean,
    storageAppFolderBrowsableByFileManagers: Boolean,
    storageCanGrantAllFilesAccess: Boolean,
    storagePendingMove: Int,
    storageMessage: String,
    onRequestAllFilesAccess: () -> Unit,
    onMoveProjects: () -> Unit,
    onClose: () -> Unit,
) {
    val chat = ChatTheme.chat
    val closeLabel = stringResource(R.string.files_info_close)
    // THE SHAPE MATTERS: a clickable parent MERGES its descendants' semantics,
    // and TestTag does not survive that merge - tag lookups inside would vanish
    // (the first device run proved it: the sheet was visibly open, its content
    // readable, yet every tag query missed). So the root is a plain Box that
    // only carries the sheet's own tag and the scrim paint; the tap-catcher is
    // a SIBLING layer under the card, and the card's texts and buttons stay
    // independent nodes. The sibling still consumes every tap the card does
    // not claim - nothing reaches the listing underneath.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .semantics { testTag = "files_info_sheet" },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, chat.toolBorder),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.files_location_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(34.dp)
                            .semantics {
                                testTag = "files_info_close"
                                contentDescription = closeLabel
                            },
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(chat.toolContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = CloseGlyph,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.size(16.dp).clearAndSetSemantics { },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = projectPath,
                    style = MonoSmall,
                    color = chat.muted,
                    modifier = Modifier.semantics { testTag = "files_location_path" },
                )
                Spacer(Modifier.height(8.dp))
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
                if (!storageVisibleToFileManagers && storageCanGrantAllFilesAccess) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.files_storage_make_visible_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = chat.muted,
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
                // The workspace folder itself is chosen in Settings (v4: the switch
                // action lives where the rest of the configuration does). What stays
                // here is what only this screen can say - now inside the sheet:
                // whether a file manager can open the folder right now, how to fix
                // that if it cannot, and the one migration that is not a preference
                // change.
                if (storageCanGrantAllFilesAccess || storagePendingMove > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (storageCanGrantAllFilesAccess) {
                            TextButton(
                                onClick = onRequestAllFilesAccess,
                                modifier = Modifier.semantics { testTag = "files_storage_grant" },
                            ) {
                                Text(stringResource(R.string.files_storage_make_visible))
                            }
                        }
                        if (storagePendingMove > 0) {
                            TextButton(
                                onClick = onMoveProjects,
                                modifier = Modifier.semantics { testTag = "files_storage_move" },
                            ) {
                                Text(stringResource(R.string.files_storage_move_action))
                            }
                        }
                    }
                }
            }
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 10.dp),
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
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { testTag = "files_up" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(chat.toolContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.files_up),
                                tint = chat.muted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.files_up),
                            style = MaterialTheme.typography.bodyMedium,
                            color = chat.muted,
                        )
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
            // v9.38 (owner): "design it to look like a real file manager, with
            // a more polished and modern UI, stylish icons, file sizes, file
            // formats and other relevant file details." A row is now the file:
            // a kind-tinted glyph, the name, what it IS (size and date, or
            // "Folder"), and the format badge. Folders lead, then names.
            items(nodes, key = { it.path }) { node ->
                val kind = fileGlyphFor(node.name, node.isDirectory)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (node.isDirectory) onOpenDir(node.path) else onOpenFile(node.path) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { testTag = if (node.isDirectory) "files_dir" else "files_file" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(kind.tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = kind.glyph,
                            contentDescription = null,
                            tint = kind.tint,
                            modifier = Modifier.size(19.dp).clearAndSetSemantics { },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = rowMetaLabel(
                            isDirectory = node.isDirectory,
                            bytes = node.bytes,
                            modifiedMs = node.modifiedMs,
                            folderWord = stringResource(R.string.files_row_folder),
                        )
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = chat.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val badge = if (node.isDirectory) "" else node.name.substringAfterLast('.', "")
                        .uppercase().takeIf { it.length in 1..4 } ?: ""
                    if (badge.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(kind.tint.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = kind.tint,
                            )
                        }
                    }
                    if (node.isDirectory) {
                        // The row itself carries the accessible name (folder
                        // glyph tinted, chevron plain): decoration is silent.
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = chat.muted,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clearAndSetSemantics { },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v9.38: what a row's icon is and what colour it wears. The `-extended`
 * icons artifact is not a dependency (see check-compose-icons.py), so the
 * glyphs are the app's own drawing (AppGlyphs) and the kind lookup is this
 * table - the same "no guessing" discipline the briefs follow: known kinds
 * get a glyph and a hue, everything else stays a quiet document.
 */
private class FileRowGlyph(val glyph: ImageVector, val tint: Color)

@Composable
private fun fileGlyphFor(name: String, isDirectory: Boolean): FileRowGlyph {
    val chat = ChatTheme.chat
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        isDirectory -> FileRowGlyph(FolderGlyph, chat.attention)
        ext in setOf(
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs",
            "c", "h", "cpp", "swift", "sh", "gradle", "json", "yaml",
            "yml", "toml", "xml", "css", "scss", "lua", "dart",
        ) -> FileRowGlyph(CodeGlyph, Color(0xFF6FA8FF))
        ext in setOf("html", "htm", "svg") -> FileRowGlyph(WebGlyph, Color(0xFF5FC9A8))
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp") ->
            FileRowGlyph(ImageGlyph, Color(0xFF9CCC7A))
        ext in setOf("md", "markdown", "txt", "log") -> FileRowGlyph(DocGlyph, Color(0xFFB9C4D6))
        ext in setOf("zip", "tar", "gz", "tgz", "bz2", "7z", "rar") ->
            FileRowGlyph(ArchiveGlyph, Color(0xFFC7A5F5))
        ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac") ->
            FileRowGlyph(AudioGlyph, Color(0xFFF0B95C))
        ext in setOf("mp4", "webm", "mov", "mkv") -> FileRowGlyph(VideoGlyph, Color(0xFFE88AB4))
        ext == "pdf" -> FileRowGlyph(PdfGlyph, Color(0xFFE0898B))
        ext in setOf("csv", "tsv", "xls", "xlsx") -> FileRowGlyph(SheetGlyph, Color(0xFF79C7C0))
        else -> FileRowGlyph(DocGlyph, chat.muted)
    }
}

/** "Folder · Mar 4, 18:32" / "4.1 KB · Mar 4, 18:32" - quiet when unknown.
 * A date from another year carries the year instead of the clock time: a
 * listing full of "Jan 3" rows from three different years would be trivia. */
private fun rowMetaLabel(isDirectory: Boolean, bytes: Long, modifiedMs: Long, folderWord: String): String {
    val bits = ArrayList<String>()
    if (isDirectory) bits.add(folderWord) else {
        humanSize(bytes).let { if (it.isNotEmpty()) bits.add(it) }
    }
    if (modifiedMs > 0L) {
        val date = java.util.Date(modifiedMs)
        val sameYear = java.util.Calendar.getInstance().let { cal ->
            cal.time = date
            cal.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        }
        bits.add(
            java.text.SimpleDateFormat(
                if (sameYear) "MMM d, HH:mm" else "MMM d, yyyy",
                java.util.Locale.getDefault(),
            ).format(date),
        )
    }
    return bits.joinToString("  \u00b7  ")
}

private fun humanSize(bytes: Long): String = when {
    bytes < 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** What the viewer can render BESIDES the source text (owner, v9.6). */
private enum class PreviewKind { NONE, WEB, MARKDOWN }

private fun previewKindOf(name: String): PreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm", "svg" -> PreviewKind.WEB
        "md", "markdown" -> PreviewKind.MARKDOWN
        else -> PreviewKind.NONE
    }
}

@Composable
private fun FileViewer(
    file: OpenFile,
    projectPath: String,
    onClose: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    val chat = ChatTheme.chat
    // v9.6 (owner): a previewable file offers both faces - the source stays
    // the default (it is what the gates and the save-copy flow reason about),
    // one toggle renders the page. HTML/SVG go through a WebView pointed at
    // the real file on disk (so relative css/js/img inside the project
    // resolve); markdown reuses the app's own renderer.
    val previewKind = if (file.binary) PreviewKind.NONE else previewKindOf(file.name)
    var showPreview by remember(file.path) { mutableStateOf(false) }
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
                if (previewKind != PreviewKind.NONE) {
                    val toggleLabel = stringResource(
                        if (showPreview) R.string.files_show_source else R.string.files_show_preview,
                    )
                    TextButton(
                        onClick = { showPreview = !showPreview },
                        modifier = Modifier.semantics {
                            testTag = "files_preview_toggle"
                            contentDescription = toggleLabel
                        },
                    ) {
                        Text(toggleLabel)
                    }
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
            } else if (showPreview && previewKind == PreviewKind.WEB) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { testTag = "files_preview" },
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // The user's own project files, rendered as a
                                // browser would. A raw WebView ships with
                                // mobile-hostile defaults (owner's finding,
                                // v9.6.1): it IGNORES the page's meta viewport,
                                // does no text autosizing and has no DOM
                                // storage - the same page then looks broken
                                // in-app while Chrome (same engine) nails it.
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                // Links stay inside the preview instead of
                                // punting every tap out to a browser.
                                webViewClient = WebViewClient()
                            }
                        },
                        update = { web ->
                            web.loadUrl("file://$projectPath/${file.path}")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (showPreview && previewKind == PreviewKind.MARKDOWN) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { testTag = "files_preview" },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        MarkdownText(source = file.text)
                    }
                }
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
