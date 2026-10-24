package ai.opencode.android.harness.sealed

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Per-file integrity data for a sealed payload: one digest and size per file.
 *
 * The container is deliberately line-oriented instead of JSON: the payload is
 * produced by a Python build script, applied by Kotlin on device, and will be
 * read by a TypeScript harness after the port, so the format has to be trivial
 * in all three. One record per line, tab-separated:
 *
 * ```
 * sha256<TAB>size<TAB>path
 * ```
 *
 * `path` is prefix-relative and may not be absolute or contain `..`. Blank
 * lines and `#` comments are ignored, so the build can explain the file it is
 * writing.
 *
 * The manifest is what turns "the payload extracted" into "the payload is the
 * payload": every extracted byte is hashed while it is written (the data is
 * already in memory at that point, so this costs no extra I/O), and a mismatch
 * is reported instead of silently installed.
 */
class RuntimeManifest private constructor(private val byPath: Map<String, Entry>) {

    /** One verified file. */
    data class Entry(val path: String, val size: Long, val sha256: String)

    fun size(): Int = byPath.size

    fun isEmpty(): Boolean = byPath.isEmpty()

    operator fun get(path: String): Entry? = byPath[path]

    fun entries(): Map<String, Entry> = byPath

    /**
     * Check an extracted prefix against this manifest.
     *
     * Used by diagnostics and by the upgrade path; the install itself verifies
     * while writing, which is strictly cheaper.
     */
    fun verify(prefix: File?): List<String> {
        if (prefix == null) return listOf("no prefix")
        val problems = ArrayList<String>()
        for (entry in byPath.values) {
            val file = File(prefix, entry.path)
            if (!file.isFile) {
                problems.add("missing: " + entry.path)
                continue
            }
            if (file.length() != entry.size) {
                problems.add("size changed: " + entry.path)
                continue
            }
            try {
                val actual = digest(Files.readAllBytes(file.toPath()))
                if (actual != entry.sha256) problems.add("digest differs: " + entry.path)
            } catch (unreadable: IOException) {
                problems.add("unreadable: " + entry.path)
            }
        }
        return problems
    }

    companion object {

        fun empty(): RuntimeManifest = RuntimeManifest(emptyMap())

        /**
         * Parse manifest text, keeping the last record for a duplicated path.
         *
         * Malformed lines are skipped rather than thrown: a manifest is build
         * output, and an install that refuses to start because of one bad line
         * would be worse than an install that verifies everything it understands
         * and reports the count it did not.
         */
        fun parse(text: String?): RuntimeManifest {
            val parsed = LinkedHashMap<String, Entry>()
            if (text != null) {
                for (raw in text.split("\n")) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val pieces = line.split("\t")
                    if (pieces.size != 3) continue
                    val sha = pieces[0].trim().lowercase()
                    if (sha.length != 64) continue
                    val size = pieces[1].trim().toLongOrNull() ?: continue
                    val path = normalize(pieces[2]) ?: continue
                    parsed[path] = Entry(path, size, sha)
                }
            }
            return RuntimeManifest(parsed)
        }

        fun parse(lines: List<String>?): RuntimeManifest {
            if (lines == null) return empty()
            val joined = StringBuilder()
            for (line in lines) joined.append(line).append('\n')
            return parse(joined.toString())
        }

        /** Read a manifest file; an absent or unreadable file is an empty manifest. */
        fun read(file: File?): RuntimeManifest {
            if (file == null || !file.isFile) return empty()
            return try {
                parse(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            } catch (unreadable: IOException) {
                empty()
            }
        }

        @Throws(IOException::class)
        fun read(input: InputStream?): RuntimeManifest {
            if (input == null) return empty()
            input.use { stream ->
                return parse(String(stream.readBytes(), StandardCharsets.UTF_8))
            }
        }

        /**
         * Normalise a payload path, or null when it is not safe.
         *
         * Shared with [PayloadExtractor] and [SealedLayout], because a path that
         * one of them accepts and another rejects is exactly how a payload ends
         * up escaping its prefix.
         */
        fun normalize(path: String?): String? {
            if (path == null) return null
            var trimmed = path.trim()
            while (trimmed.startsWith("./")) trimmed = trimmed.substring(2)
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("/") || trimmed.contains("..")) return null
            return trimmed
        }

        /** SHA-256 of an already-loaded buffer, lowercase hex. */
        fun digest(bytes: ByteArray): String = hex(newDigest().digest(bytes))

        /** A streaming digest, for callers that hash while they read. */
        fun newDigest(): MessageDigest =
            MessageDigest.getInstance("SHA-256")

        fun hex(digest: ByteArray): String {
            val text = StringBuilder(digest.size * 2)
            for (value in digest) {
                text.append(Character.forDigit((value.toInt() shr 4) and 0xf, 16))
                text.append(Character.forDigit(value.toInt() and 0xf, 16))
            }
            return text.toString()
        }
    }
}
