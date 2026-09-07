package ai.opencode.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.opencode.android.client.OpenCodeApi
import java.io.File

/**
 * Copies a user-picked file into app-private storage so the agent can read it.
 *
 * Why a copy and not the picked `Uri`: the OpenCode server resolves a file part's
 * `file:` URL itself (`packages/opencode/src/session/prompt.ts`, `case "file:"`,
 * which reads the path with `bypassCwdCheck`), and a `content:` URI is only
 * readable by THIS process through the grant the picker issued - the server
 * process would have nothing to open. Copying into `files/attachments` gives the
 * server a path it can read, keeps the original untouched, and needs no storage
 * permission (it is the app's own private directory).
 *
 * The app never parses, converts or re-encodes the content: bytes in, bytes out,
 * and the mime type is whatever the resolver reported. Images are recognised by
 * the server (`mime.startsWith("image/")`), not by us.
 */
internal object AttachmentStager {

    private const val DIR = "attachments"
    private const val MAX_BYTES = 24L * 1024L * 1024L

    /** Stage [uri]; null when it cannot be read (the caller shows nothing new). */
    fun stage(context: Context, uri: Uri): OpenCodeApi.Attachment? = runCatching {
        val resolver = context.contentResolver
        val display = displayName(context, uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory) dir.mkdirs()
        val safe = safeName(display)
        val target = File(dir, "${System.currentTimeMillis()}-$safe")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    // A hard cap rather than a silent truncation: refuse the file and
                    // leave nothing behind, so the agent is never handed a half copy.
                    if (total > MAX_BYTES) {
                        output.close()
                        target.delete()
                        return@runCatching null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: return@runCatching null
        OpenCodeApi.Attachment(
            filename = safe,
            mime = mime,
            url = FILE_SCHEME + target.absolutePath,
            sizeBytes = target.length(),
        )
    }.getOrNull()

    private const val FILE_SCHEME = "file://"

    private fun displayName(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /** Filesystem-safe, and short enough to stay readable in a chip. */
    private fun safeName(raw: String): String {
        val cleaned = raw.trim().map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .trim('_', '.')
        return cleaned.take(60).ifEmpty { "attachment" }
    }
}
