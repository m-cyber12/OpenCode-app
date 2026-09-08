package ai.opencode.android.projects

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

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
