package ai.opencode.android.projects

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * SAF (Storage Access Framework) import/export for project folders.
 *
 * The app never asks for broad storage permission and never exposes the whole
 * Android filesystem to the agent: a project only ever lives under the
 * app-private `files/workspaces` root. What a user *can* do with the outside
 * world is:
 *
 *  * **import** — pick a document tree in the system picker; its contents are
 *    copied into a fresh workspace directory (byte-capped, symlinks skipped, so
 *    the import can neither fill the device nor smuggle a path outside the
 *    workspace). This is the only "open an existing folder" the app offers.
 *  * **export** — zip a workspace into a location the user picks, so they can
 *    take their files out of the sandbox if they want.
 *  * **publish** (Phase 10 continuation) — mirror a project's files into a folder
 *    the user picks, one file per file, so the agent's output is browsable in any
 *    file manager on any Android version. This exists because the app-specific
 *    external directory (where projects live now) is still not browsable by other
 *    *apps* on Android 11+; the platform's own restriction, not this app's.
 *
 * Both go through the platform's own file pickers, so the app holds no URI grant
 * longer than the operation takes and touches only what the user selected.
 */
class SafProjectTransfer(
    private val context: Context,
    private val workspacesRoot: File,
) {

    /** Hard cap on an imported tree; anything over it is refused, nothing partial. */
    private val maxImportBytes = 512L * 1024L * 1024L

    data class ImportResult(val name: String, val copiedBytes: Long, val fileCount: Int)

    /**
     * Copy the picked document tree into `workspacesRoot/name`. The caller is
     * responsible for choosing a non-colliding [name] (via
     * [ProjectStore.uniqueName]); a pre-existing directory is NOT overwritten.
     *
     * @throws IllegalArgumentException when the uri is not a document tree.
     * @throws ProjectIo.TooLarge when the tree exceeds [maxImportBytes].
     */
    fun importTree(uri: Uri, name: String): ImportResult {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("the picker did not return a folder")
        val dest = File(workspacesRoot, name)
        if (dest.exists()) throw IllegalArgumentException("$name already exists")
        dest.mkdirs()
        var bytes = 0L
        var count = 0

        fun walk(doc: DocumentFile, into: File) {
            for (child in doc.listFiles()) {
                val childName = child.name ?: continue
                if (child.isDirectory) {
                    val sub = File(into, childName)
                    sub.mkdirs()
                    walk(child, sub)
                } else {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        bytes += ProjectIo.writeStream(input, File(into, childName), maxImportBytes - bytes)
                        count++
                    }
                }
            }
        }

        try {
            walk(tree, dest)
        } catch (t: Throwable) {
            // A failed import must not leave a half-copied workspace behind: it
            // would be discovered by ProjectStore.projects() and show up in the
            // list as if the import had succeeded.
            ProjectIo.deleteTree(dest)
            throw t
        }
        return ImportResult(name = name, copiedBytes = bytes, fileCount = count)
    }

    /**
     * Phase 10 continuation: mirror [projectDir] into a folder the user picked
     * (`OpenDocumentTree`), so the agent's files exist where every file manager on
     * every Android version can see them.
     *
     * Why a copy rather than "just put the project there": the agent's runtime is
     * POSIX and needs real paths it can `open()`/`write()`/`chmod`, and on API 30+
     * an app cannot write into shared storage by path without
     * `MANAGE_EXTERNAL_STORAGE` - a permission Play restricts to a narrow list of
     * app types, which this app does not request. SAF gives URI-based access to
     * the user's chosen folder, so this writes the tree there and leaves the live
     * project where the agent can work on it.
     *
     * Semantics: existing files are overwritten, files deleted in the project are
     * NOT deleted from the mirrors previous publish (a published copy is an
     * export, not a sync), directories are created as needed, and symlinks are
     * skipped (a symlink could point out of the workspace - the same rule the
     * import path follows). Returns the number of files written and the total
     * bytes.
     */
    fun publishTree(projectDir: File, treeUri: Uri, intoName: String): PublishResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("the picker did not return a folder")
        val target = tree.findFile(intoName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(intoName)
            ?: throw IllegalStateException("could not create $intoName in the picked folder")
        var files = 0
        var bytes = 0L
        walk(projectDir) { file, rel ->
            var dir = target
            val parts = rel.split('/').dropLast(1)
            for (part in parts) {
                dir = dir.findFile(part)?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(part)
                    ?: throw IllegalStateException("could not create $part")
            }
            val doc = dir.findFile(file.name)?.takeIf { !it.isDirectory }
                ?: dir.createFile(mimeOf(file.name), file.name)
                ?: throw IllegalStateException("could not create ${file.name}")
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                FileInputStream(file).use { input -> bytes += input.copyTo(out) }
            } ?: throw IllegalStateException("could not write ${file.name}")
            files++
        }
        return PublishResult(files = files, bytes = bytes, folder = intoName)
    }

    data class PublishResult(val files: Int, val bytes: Long, val folder: String)

    /**
     * Every regular file under [dir], depth-first, with its workspace-relative
     * path (the shape SAF needs) - symlinks skipped.
     */
    private fun walk(dir: File, rel: String = "", onFile: (File, String) -> Unit) {
        for (child in dir.listFiles().orEmpty()) {
            val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isDirectory) walk(child, childRel, onFile) else if (child.isFile) onFile(child, childRel)
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "text/plain"
    }

    /**
     * Copy ONE workspace file to [targetUri] (an SAF `CreateDocument` target):
     * the in-app file browser's "Save a copy", for a user who wants that single
     * file somewhere a file manager can reach. Returns the number of bytes written.
     */
    fun saveFileCopy(file: File, targetUri: Uri): Long {
        var bytes = 0L
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
            FileInputStream(file).use { input -> bytes = input.copyTo(out) }
        } ?: throw IllegalStateException("cannot open the save target")
        return bytes
    }

    /**
     * Zip [project]'s directory and write it to [targetUri] (a
     * `CreateDocument` target). The zip is a plain files tree; empty directories
     * are recorded, symlinks are skipped (they cannot point out of the workspace).
     */
    fun exportZip(project: Project, targetUri: Uri) {
        val tmp = File(context.cacheDir, "export-${project.name}-${System.currentTimeMillis()}.zip")
        try {
            ProjectIo.zipTree(project.dir, tmp)
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                FileInputStream(tmp).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("cannot open the export target")
        } finally {
            tmp.delete()
        }
    }
}
