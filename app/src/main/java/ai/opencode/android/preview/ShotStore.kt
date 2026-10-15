package ai.opencode.android.preview

import java.io.File

/**
 * v9.12 (owner): screenshots "organized and categorized within the main
 * project workspace, rather than a single screenshot". v9.13 (owner): they
 * must live NEXT TO the project's other files, not inside hidden .preview -
 * so the gallery is a plain `screenshots/` folder at the project root:
 *
 *   screenshots/001-login-page.png
 *   screenshots/002-after-dark-mode.png
 *   ...
 *
 * Sequence numbers are capture order (count of existing shots + 1), the slug
 * is the agent's name made filesystem-safe. `.preview/latest.png` stays
 * untouched as the always-current quick look.
 */
object ShotStore {

    const val SHOTS_DIR = "screenshots"

    /** v9.36: the downscaled agent view, refreshed by every capture/pane load. */
    const val AGENT_VIEW = "latest.jpg"
    private const val AGENT_MAX_W = 720
    private const val AGENT_JPEG_Q = 72
    private const val MAX_SLUG = 48

    /** Lowercase, `[a-z0-9-]` only, runs collapsed, trimmed; "shot" when nothing survives. */
    fun slug(name: String): String {
        val cleaned = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_SLUG)
            .trim('-')
        return cleaned.ifEmpty { "shot" }
    }

    /** The next file in capture order inside `<projectRoot>/screenshots/`. */
    fun nextFile(projectRoot: File, name: String): File {
        val shots = File(projectRoot, SHOTS_DIR)
        shots.mkdirs()
        val existing = shots.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.size ?: 0
        val seq = (existing + 1).toString().padStart(3, '0')
        return File(shots, "$seq-${slug(name)}.png")
    }

    /**
     * v9.36 (owner): the agent's cheap pixel channel. The full-size PNG is
     * the human gallery artifact; what the model may read is this downscaled
     * JPEG - same frame, a fraction of the bytes, and upstream re-sends
     * every read image on every later step, so the small copy is not a
     * nicety, it is the difference between an affordable loop and the
     * 20-screenshot spiral the owner hit.
     */
    fun writeAgentJpeg(bitmap: android.graphics.Bitmap, previewDir: File, maxWidth: Int = AGENT_MAX_W): File {
        previewDir.mkdirs()
        val scale = if (bitmap.width > maxWidth) maxWidth.toFloat() / bitmap.width else 1f
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val out = File(previewDir, AGENT_VIEW)
        out.outputStream().use { o ->
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, AGENT_JPEG_Q, o)
        }
        if (small !== bitmap) small.recycle()
        return out
    }

    /**
     * v9.36: a signature of what the frame LOOKS like - 16x16 downsample,
     * SHA-1, first 12 hex. `unchanged-vs-prev` between two captures means
     * the edit did not reach the render (stale server, wrong path): the one
     * fact that stops the recapture spiral dead.
     */
    fun frameSig(bitmap: android.graphics.Bitmap): String {
        val tiny = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val px = IntArray(16 * 16)
        tiny.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (tiny !== bitmap) tiny.recycle()
        val buf = java.nio.ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        val md5 = java.security.MessageDigest.getInstance("SHA-1").digest(buf.array())
        return md5.joinToString("") { b -> "%02x".format(b) }.take(12)
    }
}
