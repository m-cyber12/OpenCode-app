package ai.opencode.android.harness.sealed

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Applies the sealed layout this APK shipped.
 *
 * The app has always run its tools the sealed way - `bin/git` is a symlink into
 * the native library directory, because app data is no-exec on API 29+ - but it
 * did so from a hard-coded table in `RuntimeManager`. Now the table is an asset
 * the payload build generates (`sealed-exec-<abi>.txt`), and the linking is done
 * by [SealedLayout]: the same code that will install a userland, with the same
 * idempotence, the same refusal to invent a missing library, and - the part the
 * old table could not do - an [ExecAudit] that can *prove* afterwards that
 * nothing in the prefix needs the data-directory exec exemption.
 *
 * The behaviour is unchanged on purpose: same five links, same paths. What
 * changes is that the layout is now data shipped by the build rather than
 * knowledge buried in a class, so the device gates can assert on it.
 */
object HarnessSeal {

    /** The asset the payload build writes for one ABI. */
    fun manifestAssetName(abi: String): String = "sealed-exec-$abi.txt"

    /** This device's ABI, named the way the payload build names it. */
    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * Link every executable the manifest names into [prefix].
     *
     * Returns null when the asset is absent or unreadable, so the caller can
     * fall back to its own table and say loudly that it did; a missing asset is
     * a packaging bug and must never look like "this device has no git".
     */
    fun apply(context: Context, prefix: File, nativeLibraryDir: File): SealedLayout.Result? {
        val links = readManifest(context) ?: return null
        return SealedLayout.apply(prefix, nativeLibraryDir, links)
    }

    /** The manifest shipped in this APK, or null when there is none. */
    fun readManifest(context: Context): List<SealedLayout.Link>? {
        for (name in listOf(manifestAssetName(abi()), FALLBACK_ASSET)) {
            val text = try {
                context.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (absent: IOException) {
                continue
            }
            return SealedLayout.parse(text)
        }
        return null
    }

    private const val FALLBACK_ASSET = "sealed-exec.txt"
}
