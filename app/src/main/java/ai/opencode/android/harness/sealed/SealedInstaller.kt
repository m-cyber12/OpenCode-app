package ai.opencode.android.harness.sealed

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Applies a sealed payload to a prefix: extract the data, recreate the links,
 * then point every executable at the library the APK shipped.
 *
 * This is the whole mechanism of a publishable harness, in one class, with no
 * Android API in it:
 *
 * ```
 * assets/userland-<abi>.zip        -> data files under the prefix
 * assets/sealed-manifest-<abi>.txt -> sha256 + size per data file (verified while written)
 * assets/sealed-symlinks-<abi>.txt -> links whose target is data (versioned .so, scripts)
 * assets/sealed-exec-<abi>.txt     -> prefix path -> lib<name>.so in the APK
 * ```
 *
 * The result is a normal Termux-shaped prefix in which nothing that the kernel
 * executes came from app storage: every executable is a symlink into the native
 * library directory, which is the one place an app targeting API 29+ may
 * `execve()` from. Everything else is data, which is unrestricted.
 *
 * The Android-only half (picking the ABI, opening assets, deciding where the
 * prefix lives) stays outside this class as a thin adapter.
 */
object SealedInstaller {

    /** Everything an install needs, as plain values. */
    class Request {
        var prefix: File? = null
        var nativeLibraryDirectory: File? = null
        /** Zip of read-only files; consumed and closed. */
        var payload: InputStream? = null
        /** `sha256<TAB>size<TAB>path` lines; may be null. */
        var manifest: InputStream? = null
        /** `path<TAB>library` lines. */
        var execManifest: List<String>? = null
        /** `path<TAB>target` lines. */
        var linkManifest: List<String>? = null
        var progress: PayloadExtractor.Listener? = null
    }

    /** What an install produced, for the log, the audit and the tests. */
    class Result(
        val files: Int,
        val verified: Int,
        val bytes: Long,
        val links: Int,
        val executables: Int,
        val alreadySealed: Int,
        val problems: List<String>
    ) {
        /** True when the prefix is usable: sealed, verified and problem-free. */
        fun ok(): Boolean = problems.isEmpty() && executables + alreadySealed > 0

        fun summary(): String =
            "sealed payload: $files data files ($bytes bytes, $verified verified), " +
                "$links data links, ${executables + alreadySealed} executables from the APK" +
                if (problems.isEmpty()) "" else ", problems: $problems"
    }

    /**
     * Install a payload.
     *
     * @throws IOException when the prefix or the archives cannot be read at all;
     *         per-file failures are collected in [Result.problems] instead,
     *         because a single unreadable file should not discard a good install.
     */
    @Throws(IOException::class)
    fun install(request: Request): Result {
        val prefix = request.prefix ?: throw IOException("no prefix")
        val payload = request.payload ?: throw IOException("no payload")
        val problems = ArrayList<String>()

        val manifest = RuntimeManifest.read(request.manifest)
        val extracted = PayloadExtractor.extract(payload, prefix, manifest, request.progress)
        problems.addAll(extracted.problems)

        var links = 0
        for (link in parseLinks(request.linkManifest, problems)) {
            try {
                SealedLinks.create(File(prefix, link.first), File(prefix, link.second))
                links++
            } catch (failed: Exception) {
                problems.add("link " + link.first + ": " + failed.message)
            }
        }

        val sealed = SealedLayout.apply(
            prefix,
            request.nativeLibraryDirectory,
            SealedLayout.parse(join(request.execManifest))
        )
        problems.addAll(sealed.errors)
        problems.addAll(sealed.missingTargets)

        return Result(
            extracted.files,
            extracted.verified,
            extracted.bytes,
            links,
            sealed.linked,
            sealed.alreadyCorrect,
            problems
        )
    }

    /** `path<TAB>target` lines, both prefix-relative and both safe. */
    fun parseLinks(lines: List<String>?, problems: MutableList<String>): List<Pair<String, String>> {
        val links = ArrayList<Pair<String, String>>()
        if (lines == null) return links
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pieces = line.split("\\s+".toRegex())
            if (pieces.size != 2) {
                problems.add("bad link line: $line")
                continue
            }
            val path = RuntimeManifest.normalize(pieces[0])
            val target = RuntimeManifest.normalize(pieces[1])
            if (path == null || target == null) {
                problems.add("unsafe link line: $line")
                continue
            }
            links.add(path to target)
        }
        return links
    }

    private fun join(lines: List<String>?): String {
        if (lines == null) return ""
        val text = StringBuilder()
        for (line in lines) text.append(line).append('\n')
        return text.toString()
    }
}
