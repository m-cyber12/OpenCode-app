package ai.opencode.android.client

import android.content.Context
import android.content.SharedPreferences

/**
 * Which model the app should send, remembered across chats and projects.
 *
 * WHY THIS EXISTS (Phase 10 continuation v4, item 3 - "persistence bug").
 *
 * The model hint used to live only in [OpenCodeRepository.UiState], i.e. in the
 * memory of one repository instance. The repository is rebuilt whenever the open
 * project changes (each project is a different OpenCode instance directory), so
 * opening a new chat or a different project started from `model = null` and fell
 * back to [DefaultModelHint.pick], which legitimately prefers "any connected
 * provider other than the bundled one" - an arbitrary pick that has nothing to do
 * with what the user was just using. The user-visible symptom: a new chat silently
 * sends the first model of whichever provider the server lists first.
 *
 * The fix is to persist the two facts the user actually expressed:
 *
 *  * **the last model used** - written when they pick one in Settings, and also
 *    after a turn is accepted, so a model chosen implicitly on the first run
 *    becomes the remembered one as soon as it has been used for real;
 *  * **the models they starred** - the quick-switch shortlist in the chat header
 *    (item 3, second half). Starred is deliberately a separate, explicit list:
 *    "every model of every configured provider" is hundreds of entries and is not
 *    a quick switch.
 *
 * This is a presentation preference, not agent state: OpenCode still owns model
 * resolution and validation, and a stored value is only ever used as the hint the
 * client sends. If the stored provider is not in the server's own catalog any
 * more, [DefaultModelHint.resolve] ignores it rather than sending an id that
 * cannot serve.
 */
interface ModelPreference {

    /** The model the user last used, or null if they never picked one. */
    fun lastModel(): OpenCodeApi.ModelRef?

    /** Remember [ref] as the model to send next time (called on pick and on use). */
    fun rememberModel(ref: OpenCodeApi.ModelRef)

    /** The starred models, in the order they were starred. */
    fun starred(): List<OpenCodeApi.ModelRef>

    /** Star or unstar one model. Returns the new shortlist. */
    fun setStarred(ref: OpenCodeApi.ModelRef, starred: Boolean): List<OpenCodeApi.ModelRef>
}

/**
 * The on-disk form of a model reference: `provider/model`, the same spelling the
 * Settings screen and the chat header show.
 *
 * Split out as a pure object so the encoding is unit-tested without a device: a
 * model id can contain a slash (some catalog entries do - the provider prefix is
 * repeated inside the id), so the decoder has to split on the FIRST slash only,
 * and that rule deserves a test rather than a comment.
 */
object ModelRefCodec {

    fun encode(ref: OpenCodeApi.ModelRef): String = "${ref.providerID}/${ref.modelID}"

    fun decode(text: String): OpenCodeApi.ModelRef? {
        val trimmed = text.trim()
        val cut = trimmed.indexOf('/')
        if (cut <= 0 || cut == trimmed.length - 1) return null
        val provider = trimmed.substring(0, cut)
        val model = trimmed.substring(cut + 1)
        if (provider.isEmpty() || model.isEmpty()) return null
        return OpenCodeApi.ModelRef(provider, model)
    }
}

/**
 * [ModelPreference] over `SharedPreferences`, which is what the app uses.
 *
 * Two keys only. The starred list is stored as an ordered set rendered into one
 * string (star order is the order the user starred them, and that is the order
 * the quick-switch menu shows); a malformed or absent value decodes to an empty
 * list rather than throwing, because a preference store must never be able to
 * break the app it configures.
 */
class PrefsModelPreference(private val prefs: SharedPreferences) : ModelPreference {

    override fun lastModel(): OpenCodeApi.ModelRef? =
        prefs.getString(KEY_LAST_MODEL, "")?.takeIf { it.isNotEmpty() }?.let { ModelRefCodec.decode(it) }

    override fun rememberModel(ref: OpenCodeApi.ModelRef) {
        prefs.edit().putString(KEY_LAST_MODEL, ModelRefCodec.encode(ref)).apply()
    }

    override fun starred(): List<OpenCodeApi.ModelRef> =
        prefs.getString(KEY_STARRED, "")
            .orEmpty()
            .split('\n')
            .mapNotNull { ModelRefCodec.decode(it) }

    override fun setStarred(ref: OpenCodeApi.ModelRef, starred: Boolean): List<OpenCodeApi.ModelRef> {
        val current = starred().toMutableList()
        val key = ModelRefCodec.encode(ref)
        if (starred) {
            if (current.none { ModelRefCodec.encode(it) == key }) current.add(ref)
        } else {
            current.removeAll { ModelRefCodec.encode(it) == key }
        }
        prefs.edit().putString(KEY_STARRED, current.joinToString("\n") { ModelRefCodec.encode(it) }).apply()
        return current
    }

    companion object {
        const val PREFS = "model_prefs"
        const val KEY_LAST_MODEL = "last_model"
        const val KEY_STARRED = "starred_models"

        /** The app's store: one file, no secrets in it (only ids the server reported). */
        fun get(context: Context): ModelPreference =
            PrefsModelPreference(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            )
    }
}

/**
 * The same behaviour with no disk, used by unit tests and by anything that needs a
 * throwaway preference (the instrumented gates construct one per run so a test
 * never depends on the phone's real shortlist).
 */
class InMemoryModelPreference(
    private var last: OpenCodeApi.ModelRef? = null,
    private var list: List<OpenCodeApi.ModelRef> = emptyList(),
) : ModelPreference {

    override fun lastModel(): OpenCodeApi.ModelRef? = last

    override fun rememberModel(ref: OpenCodeApi.ModelRef) {
        last = ref
    }

    override fun starred(): List<OpenCodeApi.ModelRef> = list

    override fun setStarred(ref: OpenCodeApi.ModelRef, starred: Boolean): List<OpenCodeApi.ModelRef> {
        val key = ModelRefCodec.encode(ref)
        list = if (starred) {
            if (list.any { ModelRefCodec.encode(it) == key }) list else list + ref
        } else {
            list.filterNot { ModelRefCodec.encode(it) == key }
        }
        return list
    }
}
