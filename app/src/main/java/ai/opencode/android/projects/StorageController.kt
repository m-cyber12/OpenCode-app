package ai.opencode.android.projects

import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.StorageChoice
import ai.opencode.android.runtime.StorageMode
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Everything the UI needs to tell the truth about where the projects are, and the
 * three actions that can change it.
 *
 * Why this is its own class instead of a handful of helpers on the screens:
 *
 *  * a storage change is not a preference — it moves real directories, re-resolves
 *    the runtime's project root, and can leave projects behind if it half-succeeds.
 *    Keeping the whole sequence in one place ([activateAllFilesAccess],
 *    [useChosenFolder], [moveProjectsIntoPlace]) is what makes it reviewable;
 *  * the screens must never invent a visibility claim. [snapshot] returns the
 *    platform's verdict as data, gathered from [RuntimePaths] and the permission
 *    check, so the Files screen renders a fact rather than a hope;
 *  * the sequence is shared by the automatic path (a build upgrade resolving a new
 *    default root) and the explicit one (the user taps a button), so the two can
 *    not drift.
 *
 * Nothing here deletes user data: the moves go through [ProjectMigration], which
 * refuses to overwrite a name and leaves anything it could not move exactly where
 * it was.
 */
class StorageController(private val context: Context) {

    /** What the app can honestly say about project storage right now. */
    data class Snapshot(
        val mode: StorageMode,
        /** Absolute path of the live project root. */
        val rootPath: String,
        /** A file manager (Files app, third-party, MTP folder browse) can open it. */
        val visibleToFileManagers: Boolean,
        /** The platform lets apps browse `Android/data` on this Android version. */
        val appFolderBrowsableByFileManagers: Boolean,
        /** The "All files access" grant is offered and not yet held. */
        val canGrantAllFilesAccess: Boolean,
        /** A folder can be chosen with the system picker. */
        val canChooseFolder: Boolean,
        /** A SAF folder is in effect, so "use the default location" makes sense. */
        val hasChosenFolder: Boolean,
        /** Older roots that still hold projects (would be moved on activation). */
        val pendingRoots: List<String>,
        /** Total projects waiting in those roots. */
        val pendingProjects: Int,
    ) {
        val needsAttention: Boolean get() = !visibleToFileManagers || pendingRoots.isNotEmpty()
    }

    /** The result of an action, already turned into what the user should be told. */
    data class ChangeResult(
        val ok: Boolean,
        val messageKey: MessageKey,
        val moved: Int = 0,
        val failed: Int = 0,
        val from: String = "",
        val to: String = "",
        val detail: String = "",
    )

    enum class MessageKey {
        MOVED,
        MOVED_SOME_FAILED,
        NOTHING_TO_MOVE,
        FOLDER_UNUSABLE,
        GRANT_NOT_APPLIED,
    }

    private val paths: RuntimePaths get() = RuntimePaths.get(context).also { /* refreshed by callers */ }
    private val store: ProjectStore get() = ProjectStore.get(context)

    /**
     * Gather the current facts. Reads the platform (not a cache) so a grant made in
     * system settings shows up here on the next call.
     */
    fun snapshot(): Snapshot {
        val p = RuntimePaths.get(context)
        val pending = pendingRoots(p)
        return Snapshot(
            mode = p.mode,
            rootPath = p.workspaces.absolutePath,
            visibleToFileManagers = p.fileManagerVisible,
            appFolderBrowsableByFileManagers = p.mode == StorageMode.APP_EXTERNAL && p.fileManagerVisible,
            canGrantAllFilesAccess = p.mode != StorageMode.CHOSEN &&
                !StorageChoice.hasAllFilesAccess() &&
                StorageChoice.allFilesAccessIntent(context) != null,
            canChooseFolder = true,
            hasChosenFolder = StorageChoice.chosenRoot(context) != null,
            pendingRoots = pending.map { it.absolutePath },
            pendingProjects = pending.sumOf { dir -> dir.listFiles { f -> f.isDirectory }?.size ?: 0 },
        )
    }

    /**
     * The user granted All files access (from the app's button or from system
     * settings): re-resolve the layout, then move the projects that are still in a
     * fallback location.
     */
    fun activateAllFilesAccess(): ChangeResult {
        if (!StorageChoice.hasAllFilesAccess()) {
            // The user came back without granting: say so, change nothing.
            return ChangeResult(ok = false, messageKey = MessageKey.GRANT_NOT_APPLIED)
        }
        val before = RuntimePaths.get(context)
        val oldRoot = before.workspaces
        return reactivate(oldRoot)
    }

    /**
     * The user picked a folder with the system picker. The tree is only usable when
     * it resolves to a real path (primary volume) and the app can actually write
     * there; anything else is refused with the reason, not accepted and broken.
     *
     * This is a SWITCH, not a migration - see [switchTo].
     */
    fun useChosenFolder(treeUri: Uri): ChangeResult {
        val dir = StorageChoice.realPathOf(treeUri)
            ?: return ChangeResult(ok = false, messageKey = MessageKey.FOLDER_UNUSABLE, detail = "not a folder on internal storage")
        if (!StorageChoice.isUsableRoot(dir)) {
            return ChangeResult(ok = false, messageKey = MessageKey.FOLDER_UNUSABLE, detail = "cannot write here")
        }
        StorageChoice.setChosenRoot(context, dir)
        return switchTo(RuntimePaths.get(context).workspaces)
    }

    /** Forget the chosen folder: back to the default (shared storage when allowed). */
    fun useDefaultLocation(): ChangeResult {
        StorageChoice.clearChosenRoot(context)
        return switchTo(RuntimePaths.get(context).workspaces)
    }

    /**
     * Point the app at [dir] as the workspace, WITHOUT moving anything.
     *
     * The deliberate difference from [reactivate]: the user who picks another folder
     * gets a different set of projects, exactly as `cd` changes what a terminal
     * shows. The old folder is left untouched on disk - nothing is deleted, and the
     * projects in it are not dragged along - and the Settings screen then offers
     * "Move them here", which is [moveProjectsIntoPlace] and the only path in this
     * class that fills a non-empty destination.
     *
     * (The one case that still moves is [activateAllFilesAccess]: after the grant,
     * the shared location became usable and the projects that were parked in the
     * app-specific fallback are the SAME workspace the user was working in, not a
     * different one. [migrateOnStart] keeps doing the same on every app start.)
     *
     * Public because the instrumented workspace gate drives this exact function: a
     * check that reimplemented the switch in the test would prove nothing about the
     * app.
     */
    fun switchTo(dir: File): ChangeResult {
        val oldRoot = RuntimePaths.get(context).workspaces
        StorageChoice.setChosenRoot(context, dir)
        RuntimePaths.refresh()
        ProjectStore.refresh()
        val p = RuntimePaths.get(context)
        // Creates the new project root (and the rest of the layout) before anything
        // tries to write into it. No migration: `moved` is 0 by construction, and the
        // result says so, which is what the UI reads back to the user.
        runCatching { p.ensureDirs() }
        return ChangeResult(
            ok = p.workspaces.absolutePath == dir.absolutePath,
            messageKey = MessageKey.NOTHING_TO_MOVE,
            moved = 0,
            from = oldRoot.absolutePath,
            to = p.workspaces.absolutePath,
        )
    }

    /**
     * Move projects that are still in a fallback root into the live one, without
     * changing the mode. This is the "Move them here" button, and it is the same
     * migration the automatic path runs - only with the user's explicit consent to
     * fill a non-empty destination.
     */
    fun moveProjectsIntoPlace(): ChangeResult {
        val p = RuntimePaths.get(context)
        val sources = pendingRoots(p)
        if (sources.isEmpty()) return ChangeResult(ok = true, messageKey = MessageKey.NOTHING_TO_MOVE)
        var moved = 0
        var failed = 0
        for (src in sources) {
            val outcome = store.moveFrom(src)
            moved += outcome.movedCount
            failed += outcome.conflicts.size + outcome.failed.size
        }
        return ChangeResult(
            ok = failed == 0,
            messageKey = if (failed == 0) MessageKey.MOVED else MessageKey.MOVED_SOME_FAILED,
            moved = moved,
            failed = failed,
            from = sources.joinToString(", ") { it.absolutePath },
            to = p.workspaces.absolutePath,
        )
    }

    /**
     * Run the automatic migration for the CURRENT mode (called once per app start,
     * off the main thread). Safe on every start: it only acts when the live root is
     * empty and an older root still holds projects.
     */
    fun migrateOnStart(): Int = store.ensureMigrated()

    /**
     * Re-resolve the layout after a change, then move what was left behind: the
     * MIGRATION path (a grant arrived, or the app started into a fallback root).
     * An explicit folder switch does not come through here - see [switchTo].
     *
     * [oldRoot] is captured before the change because the singletons cache the
     * previously resolved paths; both caches are dropped here so nothing keeps
     * writing to the old location, and the new root is created before the move.
     */
    private fun reactivate(oldRoot: File): ChangeResult {
        RuntimePaths.refresh()
        ProjectStore.refresh()
        val p = RuntimePaths.get(context)
        // Creates the new project root (and the rest of the layout) before anything
        // tries to write into it.
        runCatching { p.ensureDirs() }
        val outcome = if (oldRoot.absolutePath == p.workspaces.absolutePath) {
            ProjectMigration.Outcome(emptyList(), emptyList(), emptyList())
        } else {
            store.moveFrom(oldRoot)
        }
        val failed = outcome.conflicts.size + outcome.failed.size
        return ChangeResult(
            ok = failed == 0,
            messageKey = when {
                outcome.movedCount == 0 && failed == 0 -> MessageKey.NOTHING_TO_MOVE
                failed == 0 -> MessageKey.MOVED
                else -> MessageKey.MOVED_SOME_FAILED
            },
            moved = outcome.movedCount,
            failed = failed,
            from = oldRoot.absolutePath,
            to = p.workspaces.absolutePath,
        )
    }

    /**
     * Roots that still hold project directories and are NOT the live one, newest
     * location first.
     *
     * v4: the SHARED default is in this list now. Before v4 an explicit switch
     * migrated the projects, so a former location could never hold anything; now that
     * a switch hides them (see [switchTo]) the folder the user came from has to be
     * offered back, or "nothing is lost" would be true only on disk. The app-specific
     * and app-private roots stay listed for the fallback -> shared migration.
     */
    private fun pendingRoots(p: RuntimePaths): List<File> =
        listOfNotNull(p.publicWorkspaces, p.externalWorkspaces, p.internalWorkspaces)
            .filter { it.absolutePath != p.workspaces.absolutePath }
            .filter { it.isDirectory && (it.listFiles { f -> f.isDirectory }?.isNotEmpty() == true) }

    companion object {
        /** Cached per context, like the other singletons the UI reaches for once. */
        @Volatile private var instance: StorageController? = null

        fun get(context: Context): StorageController =
            instance ?: synchronized(this) {
                instance ?: StorageController(context.applicationContext).also { instance = it }
            }

        /** Drop the cached controller (its only state is the context). */
        fun refresh() {
            synchronized(this) { instance = null }
        }
    }
}
