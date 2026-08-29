package ai.opencode.android.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * First-run extraction with validation and corruption recovery.
 *
 * The APK ships:
 *   - assets/runtime-manifest.json  (versions + per-file sha256/size)
 *   - assets/runtime-payload.tar.gz (server bundle, node_modules, launcher.js)
 *
 * On every start the supervisor calls [ensureExtracted]:
 *   1. read the manifest from assets (the APK is the trusted source),
 *   2. if a previous extraction matches (marker: payload version + all files
 *      present with correct sha256/size), do nothing,
 *   3. otherwise atomically re-extract into a staging dir, verify EVERY entry
 *      against the manifest, and only then swap it into place;
 *   4. any failure leaves the previous extraction untouched and is reported.
 *
 * This handles: first run, partial extraction (process killed mid-write),
 * corrupted/tampered files, and app upgrades that change the payload.
 */
class PayloadExtractor(
    private val context: Context,
    private val paths: RuntimePaths,
    private val logger: RuntimeLogger,
) {
    sealed class Result {
        data class AlreadyValid(val manifest: RuntimeManifest) : Result()
        data class Extracted(val manifest: RuntimeManifest) : Result()
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()
    }

    fun manifestFromAssets(): RuntimeManifest? = try {
        context.assets.open(MANIFEST_ASSET).use {
            RuntimeManifest.fromJson(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (t: Throwable) {
        logger.host("manifest read failed: ${t.message}")
        null
    }

    fun ensureExtracted(): Result {
        val manifest = manifestFromAssets()
            ?: return Result.Failed("runtime-manifest.json missing or unreadable inside the APK")

        if (manifest.payloadVersion != RuntimeVersion.PAYLOAD_VERSION) {
            logger.host("payload version mismatch: apk=${manifest.payloadVersion} app expects=${RuntimeVersion.PAYLOAD_VERSION} -> re-extract")
        }

        val verify = verifyExtraction(
            root = paths.filesDir,
            marker = paths.extractionMarker,
            serverBundle = paths.serverBundle,
            launcher = paths.launcher,
            manifest = manifest,
        )
        if (verify == null) {
            logger.host("extraction valid (${manifest.entries.size} files, ${manifest.opencodeVersion} @ ${manifest.opencodeCommit.take(7)})")
            return Result.AlreadyValid(manifest)
        }
        logger.host("extraction invalid ($verify) -> (re)extracting")
        return extract(manifest)
    }

    private fun extract(manifest: RuntimeManifest): Result {
        val staging = File(paths.filesDir, "payload.staging")
        val oldRoots = listOf("opencode", "node_modules", "launcher.js")
        try {
            staging.deleteRecursively()
            staging.mkdirs()

            context.assets.open(PAYLOAD_ASSET).use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { gz ->
                    unpackTar(gz, staging)
                }
            }

            // Verify every extracted entry BEFORE promoting (flat, filesDir-relative).
            for (e in manifest.entries) {
                val f = File(staging, e.path)
                if (!f.isFile) return Result.Failed("extraction incomplete: ${e.path} missing")
                if (f.length() != e.size) return Result.Failed("extraction size mismatch on ${e.path}")
                val sha = sha256(f)
                if (!sha.equals(e.sha256, ignoreCase = true))
                    return Result.Failed("extraction sha256 mismatch on ${e.path}")
            }

            // Promote: move each payload top-level entry into filesDir, keeping
            // any old copy aside until success. Same parent -> rename is atomic.
            paths.runtimeDir.mkdirs()
            for (name in oldRoots) {
                val src = File(staging, name)
                val dst = File(paths.filesDir, name)
                val bak = File(paths.filesDir, "$name.bak")
                if (!src.exists()) continue
                bak.deleteRecursively()
                if (dst.exists()) dst.renameTo(bak)
                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
                bak.deleteRecursively()
            }
            staging.deleteRecursively()

            paths.extractionMarker.writeText(
                org.json.JSONObject()
                    .put("payloadVersion", manifest.payloadVersion)
                    .put("opencodeCommit", manifest.opencodeCommit)
                    .put("extractedAt", System.currentTimeMillis())
                    .toString(),
            )
            logger.host("extraction complete: ${manifest.entries.size} files validated")
            return Result.Extracted(manifest)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            logger.host("extraction FAILED: ${t.javaClass.simpleName}: ${t.message}")
            logger.host(t.stackTraceToString().take(1500))
            return Result.Failed("extraction failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    companion object {
        const val MANIFEST_ASSET = "runtime-manifest.json"
        const val PAYLOAD_ASSET = "runtime-payload.tar.gz"

        /**
         * Pure validation (unit-testable): marker version + every manifest
         * entry's existence/size/sha256. Entries are relative to [root] (the
         * runtime dir); serverBundle/launcher are passed absolute for an
         * early, human-readable message before the full checksum sweep.
         */
        fun verifyExtraction(
            root: File,
            marker: File,
            serverBundle: File,
            launcher: File,
            manifest: RuntimeManifest,
        ): String? {
            if (!marker.exists()) return "no extraction marker (first run or wiped)"
            val stamped = try {
                val o = org.json.JSONObject(marker.readText())
                o.optInt("payloadVersion", -1)
            } catch (t: Throwable) {
                return "marker unreadable: ${t.message}"
            }
            if (stamped != manifest.payloadVersion) return "marker payloadVersion=$stamped != ${manifest.payloadVersion}"
            if (!serverBundle.isFile) return "server bundle missing: $serverBundle"
            if (!launcher.isFile) return "launcher missing: $launcher"

            for (e in manifest.entries) {
                val f = File(root, e.path)
                if (!f.isFile) return "missing ${e.path}"
                if (f.length() != e.size) return "size mismatch on ${e.path}: ${f.length()} != ${e.size}"
                val sha = sha256(f)
                if (!sha.equals(e.sha256, ignoreCase = true)) return "sha256 mismatch on ${e.path}"
            }
            return null
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Minimal POSIX ustar (and old-GNU) regular-file/dir reader. Entries
         * are restricted to the staging root (no `..` escapes). Static/pure so
         * it can be unit-tested on the JVM.
         */
        fun unpackTar(input: InputStream, destRoot: File) {
            val header = ByteArray(512)
            var zeroBlocks = 0
            while (true) {
                if (!readFully(input, header, 512)) break
                if (header.all { it.toInt() == 0 }) {
                    zeroBlocks++
                    if (zeroBlocks >= 2) break
                    continue
                }
                zeroBlocks = 0
                val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000').trim()
                val type = header[156].toInt().toChar()
                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).substringBefore('\u0000').trim()
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val prefix = String(header, 345, 155, Charsets.US_ASCII).substringBefore('\u0000').trim()
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                val target = File(destRoot, fullName).canonicalFile
                val rootCanon = destRoot.canonicalFile
                if (target != rootCanon && !target.path.startsWith(rootCanon.path + File.separator)) {
                    throw SecurityException("tar entry escapes staging: $fullName")
                }
                when (type) {
                    '0', '\u0000' -> { // regular file
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            var left = size
                            val buf = ByteArray(8192)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IllegalStateException("truncated tar entry: $fullName")
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) readFully(input, ByteArray(pad.toInt()), pad.toInt())
                    }
                    '5' -> target.mkdirs()   // directory
                    'x', 'g' -> skipBytes(input, size)  // pax headers — skip
                    else -> skipBytes(input, size)      // symlink/hardlink/etc.
                }
            }
        }

        private fun skipBytes(input: InputStream, size: Long) {
            val pad = (512 - (size % 512)) % 512
            var left = size + pad
            val buf = ByteArray(8192)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray, n: Int): Boolean {
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) return false
                read += r
            }
            return true
        }
    }
}
