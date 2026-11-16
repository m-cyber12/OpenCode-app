package ai.opencode.android.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Two read-only payloads, extracted on every start:
 *
 * 1. The v2 server bundle, `assets/node26-v2.zip` (opencode.mjs, assets/,
 *    launcher-v2.mjs), extracted by [ensureExtractedV2]:
 *    - if a complete extraction exists (marker `.complete`, entry files present),
 *      do nothing;
 *    - otherwise wipe [RuntimePaths.v2Root], extract the zip, and refuse any entry
 *      whose canonical path leaves the root (zip-slip);
 *    - mark the extraction complete only after every entry is written.
 *    An interrupted extraction has no marker, so the next start wipes and redoes it.
 *
 * 2. The tool data for the sealed agent tools, `assets/runtime-payload.tar.gz`
 *    (`usr/`, e.g. usr/lib/python3.13.zip), extracted by [ensureToolData]. Only the
 *    manifest's `usr/` entries are read from the tar; each one is checked against
 *    `runtime-manifest.json` (size and sha256) before it is promoted. The server
 *    bundle that the v1 Bun server used to need is not in this tar any more.
 */
class PayloadExtractor(
    private val context: Context,
    private val paths: RuntimePaths,
    private val logger: RuntimeLogger,
) {
    sealed class Result {
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()

        /** The server bundle is in place. [fresh] is true when this call extracted it. */
        data class V2Ready(val fresh: Boolean) : Result()

        /** The tool data is in place. [fresh] is true when this call extracted it. */
        data class ToolsReady(val fresh: Boolean) : Result()
    }

    fun ensureExtractedV2(): Result {
        if (paths.v2Marker.isFile && paths.v2Entry.isFile && paths.v2Launcher.isFile) {
            return Result.V2Ready(fresh = false)
        }
        return try {
            paths.v2Root.deleteRecursively()
            paths.v2Root.mkdirs()
            val base = paths.v2Root.canonicalPath + File.separator
            context.assets.open(ServerEngine.V2_ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        val out = File(paths.v2Root, item.name).canonicalFile
                        if (!out.path.startsWith(base)) {
                            return Result.Failed("unsafe entry in ${ServerEngine.V2_ASSET}: ${item.name}")
                        }
                        if (item.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (!paths.v2Entry.isFile || !paths.v2Launcher.isFile) {
                return Result.Failed("v2 payload incomplete: opencode.mjs or launcher-v2.mjs missing")
            }
            paths.v2Marker.writeText("ok")
            logger.host("v2 bundle extracted to ${paths.v2Root.name}")
            Result.V2Ready(fresh = true)
        } catch (t: Throwable) {
            Result.Failed("v2 payload extraction failed: ${t.message}", t)
        }
    }

    /**
     * Sealed-tool data (`usr/`). Valid when the extraction marker carries the
     * manifest's payloadVersion and every `usr/` file is present with its size;
     * otherwise only the `usr/` entries are read from the tar and verified.
     */
    fun ensureToolData(): Result {
        val manifest = manifestFromAssets()
            ?: return Result.Failed("runtime-manifest.json missing or unreadable inside the APK")
        RuntimeVersion.validateManifest(manifest)?.let { problem ->
            logger.host("payload manifest incompatible with app/versions.lock: $problem")
            return Result.Failed("payload manifest version mismatch: $problem")
        }
        val tools = manifest.entries.filter { it.path.startsWith(TOOLS_ROOT) }
        if (tools.isEmpty()) return Result.Failed("payload manifest lists no tool data ($TOOLS_ROOT)")
        if (toolDataProblem(manifest, tools) == null) return Result.ToolsReady(fresh = false)
        logger.host("tool data invalid (${toolDataProblem(manifest, tools)}) -> (re)extracting")
        return extractTools(manifest, tools)
    }

    private fun toolDataProblem(manifest: RuntimeManifest, tools: List<ManifestEntry>): String? {
        if (!paths.extractionMarker.isFile) return "no extraction marker (first run or wiped)"
        val stamped = try {
            JSONObject(paths.extractionMarker.readText()).optInt("payloadVersion", -1)
        } catch (t: Throwable) {
            return "marker unreadable: ${t.message}"
        }
        if (stamped != manifest.payloadVersion) return "marker payloadVersion=$stamped != ${manifest.payloadVersion}"
        for (e in tools) {
            if (!RuntimeManifest.isSafeRelativePath(e.path)) return "unsafe manifest path: ${e.path}"
            val f = File(paths.filesDir, e.path)
            if (!f.isFile) return "missing ${e.path}"
            if (f.length() != e.size) return "size mismatch on ${e.path}: ${f.length()} != ${e.size}"
        }
        return null
    }

    private fun extractTools(manifest: RuntimeManifest, tools: List<ManifestEntry>): Result {
        val staging = File(paths.filesDir, "tools.staging")
        return try {
            staging.deleteRecursively()
            staging.mkdirs()
            val payload = openPayloadStream()
                ?: return Result.Failed("runtime-payload asset missing inside the APK (tried runtime-payload.tar.gz / .tar)")
            payload.use { stream ->
                unpackTar(stream, staging) { name -> name.startsWith(TOOLS_ROOT) }
            }
            for (e in tools) {
                if (!RuntimeManifest.isSafeRelativePath(e.path)) throw SecurityException("unsafe manifest path: ${e.path}")
                val f = File(staging, e.path)
                if (!f.isFile) return Result.Failed("tool data incomplete: ${e.path} missing")
                if (f.length() != e.size) return Result.Failed("tool data size mismatch on ${e.path}")
                if (!sha256(f).equals(e.sha256, ignoreCase = true)) {
                    return Result.Failed("tool data sha256 mismatch on ${e.path}")
                }
            }
            val dst = File(paths.filesDir, TOOLS_ROOT.trimEnd('/'))
            val bak = File(paths.filesDir, TOOLS_ROOT.trimEnd('/') + ".bak")
            val src = File(staging, TOOLS_ROOT.trimEnd('/'))
            bak.deleteRecursively()
            if (dst.exists()) dst.renameTo(bak)
            if (!src.renameTo(dst)) src.copyRecursively(dst, overwrite = true)
            bak.deleteRecursively()
            staging.deleteRecursively()
            paths.extractionMarker.writeText(
                JSONObject()
                    .put("payloadVersion", manifest.payloadVersion)
                    .put("opencodeCommit", manifest.opencodeCommit)
                    .put("extractedAt", System.currentTimeMillis())
                    .toString(),
            )
            logger.host("tool data extracted: ${tools.size} files validated")
            Result.ToolsReady(fresh = true)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            logger.host("tool data extraction FAILED: ${t.javaClass.simpleName}: ${t.message}")
            Result.Failed("tool data extraction failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun manifestFromAssets(): RuntimeManifest? = try {
        context.assets.open(MANIFEST_ASSET).use {
            RuntimeManifest.fromJson(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (t: Throwable) {
        logger.host("manifest read failed: ${t.message}")
        null
    }

    /**
     * Opens the payload tar stream. AAPT may store the asset already decompressed
     * (observed in CI), so both the gzip and the raw tar encodings are accepted.
     */
    private fun openPayloadStream(): InputStream? {
        for (name in listOf(PAYLOAD_ASSET, "runtime-payload.tar")) {
            val raw = try {
                context.assets.open(name)
            } catch (_: Throwable) {
                continue
            }
            val buffered = raw.buffered()
            return try {
                buffered.mark(2)
                val magic = ByteArray(2)
                val read = buffered.read(magic)
                buffered.reset()
                if (read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(buffered, 1 shl 16)
                } else {
                    buffered
                }
            } catch (t: Throwable) {
                raw.close()
                return null
            }
        }
        return null
    }

    companion object {
        const val MANIFEST_ASSET = "runtime-manifest.json"
        const val PAYLOAD_ASSET = "runtime-payload.tar.gz"

        /** The sealed tools' read-only data half: the only tar root that is extracted. */
        const val TOOLS_ROOT = "usr/"

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
        fun unpackTar(input: InputStream, destRoot: File, keep: (String) -> Boolean = { true }) {
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
                    '0', '\u0000' -> if (!keep(fullName)) skipBytes(input, size) else { // regular file
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
                    '5' -> if (keep(fullName)) target.mkdirs()   // directory
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
