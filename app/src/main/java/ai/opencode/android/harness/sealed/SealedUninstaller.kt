package ai.opencode.android.harness.sealed

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Removes a sealed payload from a prefix, the half everybody forgets.
 *
 * An optional pack that can be installed but not removed is not a feature, it
 * is a leak. Because every payload ships the list of what it put down (a
 * manifest of files with sizes, and a manifest of the executables it linked),
 * removal is exact: those paths and no others. That property is what makes it
 * safe to offer in a UI next to a core runtime that must survive.
 *
 * The opposite of [SealedInstaller]: it never guesses, it never deletes a
 * directory, and it reports the bytes it actually reclaimed rather than the
 * bytes it hoped to.
 */
object SealedUninstaller {

    /** What removal produced. */
    class Result(
        val files: Int,
        val bytes: Long,
        val problems: List<String>
    ) {
        fun ok(): Boolean = problems.isEmpty()

        fun summary(): String =
            "removed $files files, reclaimed $bytes bytes" +
                if (problems.isEmpty()) "" else ", problems: $problems"
    }

    /**
     * Delete exactly what a payload installed.
     *
     * @param execLines `path<TAB>library` records, as written by the build
     * @param manifestLines `sha256<TAB>size<TAB>path` records
     */
    fun remove(prefix: File?, execLines: List<String>?, manifestLines: List<String>?): Result {
        val problems = ArrayList<String>()
        if (prefix == null) return Result(0, 0, listOf("no prefix"))
        var files = 0
        var bytes = 0L

        // Executables are symlinks the installer created; the targets live in
        // the APK and must never be touched.
        for (raw in execLines ?: emptyList()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val path = RuntimeManifest.normalize(line.split("\\s+".toRegex())[0]) ?: continue
            val link = File(prefix, path)
            if (!link.exists() && !Files.isSymbolicLink(link.toPath())) continue
            try {
                Files.deleteIfExists(link.toPath())
                files++
            } catch (refused: IOException) {
                problems.add("could not delete " + path + ": " + refused.message)
            }
        }

        for (raw in manifestLines ?: emptyList()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pieces = line.split("\t")
            if (pieces.size != 3) continue
            val size = pieces[1].trim().toLongOrNull() ?: continue
            val path = RuntimeManifest.normalize(pieces[2]) ?: continue
            val file = File(prefix, path)
            if (!file.isFile) continue
            // A file whose contents are not the ones this payload wrote belongs
            // to something else (another payload, or the user): leave it.
            if (file.length() != size) continue
            try {
                Files.delete(file.toPath())
                files++
                bytes += size
            } catch (refused: IOException) {
                problems.add("could not delete " + path + ": " + refused.message)
            }
        }
        return Result(files, bytes, problems)
    }
}
