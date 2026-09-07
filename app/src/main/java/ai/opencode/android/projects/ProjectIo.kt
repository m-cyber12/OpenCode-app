package ai.opencode.android.projects

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The filesystem operations behind project management, kept free of Android APIs
 * so the destructive and size-capped behaviour is covered by JVM unit tests.
 *
 * Everything this class is asked to do happens on paths the caller already
 * resolved under the app-private `files/workspaces` root, so none of it can touch
 * shared storage by accident. Symlinks are skipped during copy/zip: a symlink in
 * an imported tree could otherwise point at a file outside the workspace, and the
 * workspace boundary (OpenCode's own instance-directory enforcement, plus the app
 * keeping its internals elsewhere) must stay true of the bytes on disk too.
 */
object ProjectIo {

    /** Raised when a copy would exceed the configured cap. Nothing is left partial. */
    class TooLarge(val limitBytes: Long, val copiedBytes: Long) : Exception(
        "copy would exceed $limitBytes bytes (already at $copiedBytes)",
    )

    /** Rename a directory; falls back to copy+delete when a plain rename fails. */
    fun renameDir(from: File, to: File): Boolean {
        if (!from.isDirectory || to.exists()) return false
        if (from.renameTo(to)) return true
        // A same-volume renameTo can still fail on some filesystems; a copy+delete
        // is equivalent for app-private storage (small trees).
        val copied = try {
            copyTree(from, to, Long.MAX_VALUE)
            true
        } catch (t: Throwable) {
            false
        }
        if (copied) deleteTree(from)
        return copied && to.isDirectory
    }

    fun deleteTree(dir: File) {
        if (!dir.exists()) return
        if (dir.isDirectory) dir.listFiles()?.forEach { deleteTree(it) }
        dir.delete()
    }

    /** Total bytes under [dir] (directories count 0, their contents count their own length). */
    fun sizeOf(dir: File): Long =
        if (dir.isDirectory) dir.listFiles()?.sumOf { sizeOf(it) } ?: 0L else dir.length()

    /**
     * Recursively copy [src] into [dst] (created if needed), skipping symlinks and
     * enforcing a byte cap so an import cannot fill the device.
     *
     * When the cap is exceeded the destination tree is removed before [TooLarge]
     * propagates: a half-copied file or directory must not linger as if the copy
     * had completed (a failed import would otherwise show up in the project list
     * as a phantom workspace).
     *
     * @return the number of bytes copied.
     */
    fun copyTree(src: File, dst: File, maxBytes: Long): Long = try {
        copyRec(src, dst, maxBytes)
    } catch (t: TooLarge) {
        deleteTree(dst)
        throw t
    }

    private fun copyRec(src: File, dst: File, maxBytes: Long): Long {
        if (!src.exists()) return 0L
        var total = 0L
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                if (isSymlink(child)) return@forEach
                total += copyRec(child, File(dst, child.name), maxBytes - total)
            }
        } else {
            dst.parentFile?.mkdirs()
            total += copyFile(src, dst, maxBytes)
        }
        if (total > maxBytes) throw TooLarge(maxBytes, total)
        return total
    }

    private fun isSymlink(f: File): Boolean =
        try { !f.absolutePath.equals(f.canonicalPath) } catch (t: Throwable) { true }

    private fun copyFile(src: File, dst: File, maxBytes: Long): Long {
        var n = 0L
        val buf = ByteArray(64 * 1024)
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { out ->
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    n += read
                    if (n > maxBytes) throw TooLarge(maxBytes, n)
                    out.write(buf, 0, read)
                }
            }
        }
        return n
    }

    /**
     * Write a zip of [dir] into [out]. Empty directories are recorded; symlinks are
     * skipped for the same reason [copyTree] skips them.
     *
     * @return the total bytes of file content written.
     */
    fun zipTree(dir: File, out: File): Long {
        var total = 0L
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            fun add(file: File, prefix: String) {
                val rel = prefix + file.name
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry(rel + "/"))
                    zip.closeEntry()
                    file.listFiles()?.forEach { add(it, rel + "/") }
                } else if (!isSymlink(file)) {
                    zip.putNextEntry(ZipEntry(rel))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    total += file.length()
                }
            }
            dir.listFiles()?.forEach { add(it, "") }
        }
        return total
    }

    /**
     * Write an input stream into [dst], byte-capped. Returns bytes written.
     *
     * Like [copyTree], a cap breach removes the partial destination file before
     * [TooLarge] propagates, so an import that fails on file N does not leave
     * file N behind.
     */
    fun writeStream(input: InputStream, dst: File, maxBytes: Long): Long {
        dst.parentFile?.mkdirs()
        var n = 0L
        val buf = ByteArray(64 * 1024)
        try {
            FileOutputStream(dst).use { out ->
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    n += read
                    if (n > maxBytes) throw TooLarge(maxBytes, n)
                    out.write(buf, 0, read)
                }
            }
        } catch (t: TooLarge) {
            dst.delete()
            throw t
        }
        return n
    }
}
