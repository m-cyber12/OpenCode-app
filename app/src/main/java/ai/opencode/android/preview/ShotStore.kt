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
}
