package ai.opencode.android.harness.sealed

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Extracts the data half of a sealed payload into a prefix.
 *
 * This is the piece the reference harness calls `PayloadExtractor`: a zip of
 * everything that is only _read_ (shell scripts, shared libraries, `/etc`,
 * `/share`, certificates), extracted into the app's own storage. Nothing
 * extracted here is ever `execve`-d: the executables travel as JNI libraries
 * and are linked in by [SealedInstaller].
 *
 * Three properties matter, and each is a test:
 *
 *  1. **No escape.** Absolute names and `..` are rejected, and the resolved
 *     path is checked against the prefix root as well, so a hostile zip cannot
 *     write outside it.
 *  2. **Verified while written.** When a [RuntimeManifest] entry exists for an
 *     entry, its SHA-256 and size are checked against the bytes as they stream
 *     past. No second read, no separate verification pass.
 *  3. **Idempotent.** Files are overwritten; directories are created as needed.
 *     Re-running an install after an upgrade heals in place.
 */
object PayloadExtractor {

    /** Receives progress; implementations must not throw. */
    fun interface Listener {
        fun onFile(path: String, index: Int)
    }

    /** What extraction produced. */
    class Result(
        val files: Int,
        val verified: Int,
        val bytes: Long,
        val problems: List<String>
    ) {
        fun ok(): Boolean = problems.isEmpty()

        fun summary(): String =
            "extracted $files files, $bytes bytes, $verified verified" +
                if (problems.isEmpty()) "" else ", problems: ${problems.size}"
    }

    @Throws(IOException::class)
    fun extract(
        archive: InputStream,
        prefix: File,
        manifest: RuntimeManifest?,
        listener: Listener?
    ): Result {
        val problems = ArrayList<String>()
        if (!prefix.isDirectory && !prefix.mkdirs() && !prefix.isDirectory) {
            throw IOException("cannot create prefix: $prefix")
        }
        val expected = manifest ?: RuntimeManifest.empty()
        val root = prefix.absolutePath
        val digest: MessageDigest = RuntimeManifest.newDigest()
        val buffer = ByteArray(64 * 1024)
        var files = 0
        var verified = 0
        var bytes = 0L

        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = RuntimeManifest.normalize(entry.name)
                if (name == null) {
                    if (entry.name.isNotEmpty()) {
                        problems.add("unsafe payload path: " + entry.name)
                    }
                    continue
                }
                val output = File(prefix, name)
                if (entry.isDirectory) {
                    if (!output.mkdirs() && !output.isDirectory) {
                        problems.add("cannot create $name")
                    }
                    continue
                }
                if (!output.absolutePath.startsWith(root + File.separator)) {
                    problems.add("payload path escapes the prefix: $name")
                    continue
                }
                val parent = output.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                    problems.add("cannot create parent of $name")
                    continue
                }
                digest.reset()
                var written = 0L
                FileOutputStream(output).use { file ->
                    var read = zip.read(buffer)
                    while (read != -1) {
                        file.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        read = zip.read(buffer)
                    }
                }
                files++
                bytes += written
                val expectedEntry = expected[name]
                if (expectedEntry != null) {
                    val actual = RuntimeManifest.hex(digest.digest())
                    if (written != expectedEntry.size) {
                        problems.add("size differs: $name ($written != ${expectedEntry.size})")
                    } else if (actual != expectedEntry.sha256) {
                        problems.add("digest differs: $name")
                    } else {
                        verified++
                    }
                }
                listener?.onFile(name, files)
            }
        }
        if (!expected.isEmpty() && verified != expected.size() && problems.isEmpty()) {
            // Everything present verified, but the payload did not carry every
            // file the manifest promised: that is a truncated build, not a
            // tolerable difference, because a missing stdlib breaks Python long
            // after the install reports success.
            problems.add("manifest lists ${expected.size()} files, payload carried $verified")
        }
        return Result(files, verified, bytes, problems)
    }
}
