package ai.opencode.android.projects

import java.io.File

/**
 * Moving projects between storage locations without ever losing one.
 *
 * The app changes the project root in three situations, and each one is a chance
 * to destroy a user's work if it is done carelessly:
 *
 *  1. the user grants All files access, so the root moves from `Android/data`
 *     (or app-private storage) to `Documents/OpenCode`;
 *  2. the user picks their own folder through SAF, so the root moves there;
 *  3. a build upgrade resolves a different default root than the previous build
 *     (this is what [ProjectStore.ensureMigrated] handles automatically).
 *
 * The rules encoded here, all of them the conservative option:
 *
 *  * a name that already exists at the destination is NEVER overwritten — that
 *    project stays where it is and is reported as not moved;
 *  * a directory is renamed when possible and copied-then-deleted otherwise (the
 *    two locations are usually different filesystems, where rename fails);
 *  * the source directory is removed only when it is empty, so a partial failure
 *    leaves the originals exactly where the user left them;
 *  * nothing is deleted at the destination, and nothing is merged.
 *
 * The caller decides whether a non-empty destination is acceptable: the automatic
 * migration on start refuses it (a project the user deleted must not come back),
 * while an explicit "move my projects" tap allows it (the user asked).
 */
object ProjectMigration {

    data class Outcome(
        /** Names moved into the destination. */
        val moved: List<String>,
        /** Names left in the source: the destination already had that name. */
        val conflicts: List<String>,
        /** Names whose move was attempted and failed (source kept). */
        val failed: List<String>,
        /** True when the destination was not empty and the caller forbade that. */
        val blockedByNonEmptyTarget: Boolean = false,
    ) {
        val movedCount: Int get() = moved.size
        val nothingToDo: Boolean get() = moved.isEmpty() && conflicts.isEmpty() && failed.isEmpty()
    }

    /**
     * Move every direct subdirectory of [from] into [to].
     *
     * @param allowTargetNonEmpty when false, a single existing subdirectory at the
     *   destination blocks the whole move (the automatic path); when true, existing
     *   names are skipped individually and the rest still move (the user asked).
     */
    fun moveAll(from: File, to: File, allowTargetNonEmpty: Boolean): Outcome {
        if (from.absolutePath == to.absolutePath) return Outcome(emptyList(), emptyList(), emptyList())
        if (!from.isDirectory) return Outcome(emptyList(), emptyList(), emptyList())
        val sources = from.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (sources.isEmpty()) {
            // Only the empty husk of an earlier location: safe to remove.
            ProjectIo.deleteTree(from)
            return Outcome(emptyList(), emptyList(), emptyList())
        }
        if (!allowTargetNonEmpty && (to.listFiles { f -> f.isDirectory }?.isNotEmpty() == true)) {
            return Outcome(emptyList(), sources.map { it.name }, emptyList(), blockedByNonEmptyTarget = true)
        }
        to.mkdirs()
        val moved = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (src in sources) {
            val dst = File(to, src.name)
            if (dst.exists()) {
                conflicts += src.name
                continue
            }
            if (ProjectIo.renameDir(src, dst)) moved += src.name else failed += src.name
        }
        // Clean up only what is provably empty; otherwise the leftovers stay visible
        // so the user (or a later run) can still get at them.
        if (from.listFiles()?.isEmpty() != false) ProjectIo.deleteTree(from)
        return Outcome(moved, conflicts, failed)
    }
}
