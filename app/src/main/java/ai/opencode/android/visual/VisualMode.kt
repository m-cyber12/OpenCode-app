package ai.opencode.android.visual

import android.content.Context

/**
 * How often the model may add a visual (a picture or a diagram) to a reply.
 *
 * From the "Universal Visual Intelligence Engine" spec, section 3.3. The default is
 * [BALANCED]. The choice is stored in the app's own preferences file and written into
 * the environment brief before every prompt, so the model follows the current setting.
 *
 * Only the model's behaviour changes here. Picture search is not set up yet, so a
 * visual marker does not show a picture: it is hidden and the text stands on its own.
 */
enum class VisualMode(val key: String) {
    OFF("off"),
    EXPLICIT_ONLY("explicit_only"),
    CONSERVATIVE("conservative"),
    BALANCED("balanced"),
    RICH("rich");

    companion object {
        val DEFAULT: VisualMode = BALANCED

        /** Unknown or missing keys fall back to [DEFAULT]; a stored value never breaks the app. */
        fun fromKey(key: String?): VisualMode = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Reads and writes the chosen mode. The file is the one the app's other UI preferences use. */
object VisualModePrefs {
    const val FILE = "opencode"
    const val KEY = "visual_mode"

    fun read(context: Context): VisualMode =
        VisualMode.fromKey(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
}
