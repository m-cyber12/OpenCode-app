package ai.opencode.android.harness.sealed

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Turns the prefix into a _sealed_ userland: every executable entry becomes a
 * symlink to a file the APK shipped, so nothing under the app's data directory
 * ever needs `execve()`.
 *
 * Why symlinks and not copies: the kernel resolves a symlink before it checks
 * permissions, so `.../usr/bin/git` keeps its absolute path (which apt, dpkg,
 * configure scripts and `#!` lines all hard-code) while the inode that actually
 * runs lives in the app's native library directory, the one location Android
 * allows apps targeting API 29+ to execute. Copies would be a second cost in
 * storage and would drift from the APK.
 *
 * The mapping is produced at build time (one line per executable, because only
 * the build knows which ELF became which `lib*.so`) and applied here at first
 * run. It is idempotent: re-running rewrites only what is missing or wrong, so
 * an app upgrade that adds executables heals in place.
 *
 * Manifest format, tab-separated, `#` comments, prefix-relative source path
 * first:
 *
 * ```
 * bin/bash<TAB>libbash.so
 * bin/git<TAB>libgit.so
 * ```
 */
object SealedLayout {

    /** One manifest line. */
    data class Link(val path: String, val library: String)

    /** Outcome of [apply]. */
    class Result(
        val linked: Int,
        val alreadyCorrect: Int,
        val missingTargets: List<String>,
        val errors: List<String>
    ) {
        fun ok(): Boolean = missingTargets.isEmpty() && errors.isEmpty()

        fun summary(): String =
            "sealed layout: $linked linked, $alreadyCorrect already correct, " +
                "${missingTargets.size} missing targets, ${errors.size} errors"
    }

    /** Parse a manifest; blank lines and `#` comments are ignored. */
    fun parse(manifest: String?): List<Link> {
        val links = ArrayList<Link>()
        if (manifest == null) return links
        for (raw in manifest.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var separator = line.indexOf('\t')
            if (separator < 0) separator = line.lastIndexOf(' ')
            if (separator <= 0 || separator == line.length - 1) continue
            var path = line.substring(0, separator).trim()
            val library = line.substring(separator + 1).trim()
            // Only relative paths: a manifest may not escape the prefix.
            while (path.startsWith("./")) path = path.substring(2)
            if (path.startsWith("/") || path.contains("..")) continue
            if (path.isEmpty() || library.isEmpty()) continue
            links.add(Link(path, library))
        }
        return links
    }

    /**
     * Apply [links] to [prefix].
     *
     * A link whose library is absent from [nativeLibraryDirectory] is reported,
     * not invented: that is a packaging bug (the APK lost a file), and silently
     * leaving the old binary in place would keep the install working only on
     * pre-API-29 semantics.
     */
    fun apply(prefix: File?, nativeLibraryDirectory: File?, links: List<Link>): Result {
        val missing = ArrayList<String>()
        val errors = ArrayList<String>()
        var linked = 0
        var correct = 0
        if (prefix == null || nativeLibraryDirectory == null) {
            return Result(0, 0, missing, listOf("prefix or lib dir missing"))
        }
        for (link in links) {
            val library = File(nativeLibraryDirectory, link.library)
            if (!library.isFile) {
                missing.add(link.library + " (for " + link.path + ")")
                continue
            }
            val destination = File(prefix, link.path)
            val target = library.absolutePath
            val parent = destination.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                errors.add("cannot create " + parent.absolutePath)
                continue
            }
            val destinationPath = destination.toPath()
            if (Files.isSymbolicLink(destinationPath)) {
                if (target == SealedLinks.readLink(destination)) {
                    correct++
                    continue
                }
            } else if (destination.isDirectory) {
                errors.add("refusing to replace directory " + link.path)
                continue
            }
            try {
                Files.deleteIfExists(destinationPath)
                Files.createSymbolicLink(destinationPath, library.toPath())
                linked++
            } catch (refused: IOException) {
                errors.add(link.path + ": " + refused.message)
            } catch (unsupported: UnsupportedOperationException) {
                errors.add(link.path + ": " + unsupported.message)
            }
        }
        return Result(linked, correct, missing, errors)
    }

    /** Read a manifest from a file, or null when absent. */
    fun readManifest(manifest: File?): List<Link>? {
        if (manifest == null || !manifest.isFile) return null
        return try {
            parse(String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8))
        } catch (unreadable: IOException) {
            null
        }
    }

    /** Map view, handy for the build-time generator and diagnostics. */
    fun toMap(links: List<Link>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (link in links) map[link.path] = link.library
        return map
    }
}
