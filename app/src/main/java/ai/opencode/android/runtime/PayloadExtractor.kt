package ai.opencode.android.runtime

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * First-run extraction of the v2 server bundle.
 *
 * The APK ships `assets/node26-v2.zip` (opencode.mjs, assets/, launcher-v2.mjs). On
 * every start the supervisor calls [ensureExtractedV2]:
 *   1. if a complete extraction exists (marker `.complete`, entry files present),
 *      do nothing;
 *   2. otherwise wipe [RuntimePaths.v2Root], extract the zip, and refuse any entry
 *      whose canonical path leaves the root (zip-slip);
 *   3. mark the extraction complete only after every entry is written.
 *
 * An interrupted extraction has no marker, so the next start wipes and redoes it.
 * The v1 extractor (`runtime-payload.tar.gz`, manifest checksums) was removed at the
 * switch-over together with the Bun server it fed.
 */
class PayloadExtractor(
    private val context: Context,
    private val paths: RuntimePaths,
    private val logger: RuntimeLogger,
) {
    sealed class Result {
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()

        /** The bundle is in place. [fresh] is true when this call extracted it. */
        data class V2Ready(val fresh: Boolean) : Result()
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
}
