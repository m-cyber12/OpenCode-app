package ai.opencode.android.harness.sealed

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Symlink helpers shared by the sealed installer, the layout applier and the
 * audit.
 *
 * They exist because the kernel resolves a symlink before it checks
 * permissions, so a link is the only way a file can keep its absolute prefix
 * path while the inode that actually runs lives in the app's native library
 * directory. Reading a link back is how every idempotence check in this
 * package decides whether work is already done.
 */
object SealedLinks {

    /** The literal target text of a symlink, or null when it is not one. */
    fun readLink(file: File): String? =
        try {
            Files.readSymbolicLink(file.toPath()).toString()
        } catch (notALink: IOException) {
            null
        } catch (unsupported: UnsupportedOperationException) {
            null
        }

    /** Follow a chain of symlinks to the regular file it ends at. */
    fun resolveLink(link: File?, depth: Int = 0): File? {
        if (depth > 12 || link == null || !Files.isSymbolicLink(link.toPath())) return link
        val target = readLink(link) ?: return null
        var next = File(target)
        if (!next.isAbsolute) {
            val parent = link.parentFile
            next = if (parent == null) next else File(parent, target)
        }
        if (!next.exists() && !Files.isSymbolicLink(next.toPath())) return null
        return resolveLink(next, depth + 1)
    }

    /**
     * Create (or replace) the symlink at [path] pointing at [target].
     *
     * A directory is never replaced: an executable that became a directory
     * means the prefix is not the one this app laid down, and deleting it would
     * destroy something the user put there.
     */
    @Throws(IOException::class)
    fun create(link: File, target: File) {
        val parent = link.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create " + parent.absolutePath)
        }
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }
}
