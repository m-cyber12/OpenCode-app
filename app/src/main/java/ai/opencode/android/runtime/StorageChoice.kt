package ai.opencode.android.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * The user's storage choices, in one place: which folder they want their projects
 * in, whether the app may write to shared storage at all, and how to ask for it.
 *
 * Why this is not just a preference read:
 *
 *  * A Storage Access Framework tree is a `content://` URI. The embedded OpenCode
 *    runtime is a POSIX process (git, bun, ripgrep, the `bash` tool) and needs a
 *    real path, so a chosen folder is only usable as the live project root when it
 *    can be RESOLVED to a real path on the primary shared volume — and resolution
 *    is not the same thing as permission, so [isUsableRoot] writes a probe file
 *    before anything is moved there.
 *  * "All files access" is a special app-ops permission granted in system settings,
 *    not a runtime-permission dialog, so the app has to send the user there and
 *    re-check on return ([allFilesAccessIntent], [hasAllFilesAccess]).
 *
 * Every one of these is a documented Android limitation rather than a choice made
 * here; where the app cannot do what was asked it says so in the UI instead of
 * silently writing somewhere else.
 */
object StorageChoice {

    private const val PREFS = "storage"
    private const val KEY_ROOT = "root_override"
    private const val PROBE = ".opencode-write-probe"

    /** The folder the user picked, or null when they never picked one. */
    fun chosenRoot(context: Context): File? {
        val path = prefs(context).getString(KEY_ROOT, "") ?: ""
        if (path.isEmpty()) return null
        val dir = File(path)
        // A stale choice (folder deleted, card removed) must not strand the app on a
        // root that cannot be written: fall back and let the UI re-offer the choice.
        return if (dir.isDirectory && isUsableRoot(dir)) dir else null
    }

    fun setChosenRoot(context: Context, dir: File) {
        prefs(context).edit().putString(KEY_ROOT, dir.absolutePath).apply()
    }

    fun clearChosenRoot(context: Context) {
        prefs(context).edit().remove(KEY_ROOT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All files access (`MANAGE_EXTERNAL_STORAGE`). Always false below API 30. */
    fun hasAllFilesAccess(): Boolean = runCatching {
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    }.getOrDefault(false)

    /**
     * The system screen where the user grants all-files access to this app. Prefers
     * the app-scoped page; falls back to the list when an OEM does not ship it.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pkg = context.packageName
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$pkg"))
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }

    /**
     * Can the app really create and delete files in [dir]? Checked by doing it, not
     * by asking: a SAF grant, a mounted card or a root-owned directory can all look
     * plausible and still fail on the first write the runtime attempts.
     */
    fun isUsableRoot(dir: File): Boolean {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                false
            } else {
                val probe = File(dir, PROBE)
                // Create it if it is not there, then REMOVE it: both halves matter.
                // A directory that accepts a new file but refuses deletion is not one
                // the runtime can work in, and a probe left behind would be litter in
                // the user's folder.
                val created = probe.exists() || probe.createNewFile()
                created && probe.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resolve a SAF tree to a real filesystem path, or null when that is impossible.
     *
     * Only the primary shared volume can be resolved (`primary:Documents/Dev` ->
     * `/storage/emulated/0/Documents/Dev`). An SD card or a cloud provider
     * (`content://com.android.externalstorage.documents/tree/1A2B-3C4D:...`,
     * Google Drive, ...) has no path the runtime could use, and pretending otherwise
     * would produce a project the agent silently cannot write to.
     */
    fun realPathOf(treeUri: Uri, primaryVolume: File = Environment.getExternalStorageDirectory()): File? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return realPathOfDocumentId(id, primaryVolume)
    }

    /**
     * The pure half of [realPathOf]: turn a SAF document id into a real path, or
     * null when the id does not name a folder on the primary volume.
     *
     * Split out from the URI parsing so it is covered by a JVM unit test without
     * Robolectric - this is the function that decides whether a user's chosen
     * folder can be handed to the runtime, and it is exactly the kind of code that
     * silently accepts something unusable if it is only ever exercised by hand.
     */
    fun realPathOfDocumentId(documentId: String, primaryVolume: File): File? {
        if (!documentId.startsWith("primary:")) return null
        val rel = documentId.removePrefix("primary:").trim('/')
        if (rel.isEmpty()) return null
        // Reject anything with traversal or a NUL; the id comes from the system
        // picker, but this value ends up as a working directory for shell commands.
        if (rel.contains("..") || rel.contains('\u0000')) return null
        return File(File(primaryVolume, rel).absolutePath)
    }
}
