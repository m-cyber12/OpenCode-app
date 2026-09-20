package ai.opencode.android.projects

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 10 continuation v3: moving a user's projects between storage locations.
 *
 * This is the code that runs when someone grants All files access (projects move
 * from `Android/data` into `Documents/OpenCode`) or picks a folder of their own.
 * Every rule here exists because the alternative loses someone's work:
 *
 *  * a name that already exists at the destination is never overwritten - that
 *    project stays put and is reported;
 *  * the source directory is removed only when it is provably empty, so a partial
 *    failure leaves the originals exactly where they were;
 *  * the automatic path (a build upgrade) refuses a non-empty destination, while
 *    the explicit path (the user tapped "move them here") is allowed to fill one -
 *    the difference between a helpful move and resurrecting a deleted project.
 */
class ProjectMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): File = tmp.newFolder(name)

    private fun project(root: File, name: String, file: String = "a.txt", body: String = "x"): File {
        val p = File(root, name).apply { mkdirs() }
        File(p, file).writeText(body)
        return p
    }

    @Test
    fun anExplicitMoveTakesEverythingAndRemovesTheDrainedSource() {
        val from = dir("old")
        project(from, "alpha", "main.kt", "fun main() = Unit")
        project(from, "beta", "notes.md", "# beta")
        val to = dir("new")

        val outcome = ProjectMigration.moveAll(from, to, allowTargetNonEmpty = true)

        assertEquals(listOf("alpha", "beta"), outcome.moved)
        assertTrue(outcome.conflicts.isEmpty())
        assertTrue(outcome.failed.isEmpty())
        assertEquals("fun main() = Unit", File(File(to, "alpha"), "main.kt").readText())
        assertFalse("an emptied source directory is cleaned up", from.exists())
    }

    @Test
    fun theAutomaticPathRefusesANonEmptyDestination() {
        val from = dir("old")
        project(from, "alpha")
        val to = dir("new")
        project(to, "existing")

        val outcome = ProjectMigration.moveAll(from, to, allowTargetNonEmpty = false)

        assertTrue(outcome.blockedByNonEmptyTarget)
        assertTrue("nothing may move into an occupied root", outcome.moved.isEmpty())
        assertTrue("the untouched source stays", File(from, "alpha").isDirectory)
    }

    @Test
    fun anExplicitMoveSkipsNameCollisionsAndKeepsBothCopies() {
        val from = dir("old")
        project(from, "alpha", "old.txt", "old")
        project(from, "beta", "b.txt", "b")
        val to = dir("new")
        project(to, "alpha", "new.txt", "new")

        val outcome = ProjectMigration.moveAll(from, to, allowTargetNonEmpty = true)

        assertEquals(listOf("beta"), outcome.moved)
        assertEquals(listOf("alpha"), outcome.conflicts)
        // The destination copy is untouched - a move must never overwrite a project.
        assertEquals("new", File(File(to, "alpha"), "new.txt").readText())
        // ...and the source copy stays so nothing is lost, so the source survives.
        assertEquals("old", File(File(from, "alpha"), "old.txt").readText())
        assertTrue("a source that still holds projects is kept", from.isDirectory)
    }

    @Test
    fun anEmptySourceIsJustRemovedAndNothingIsReportedAsMoved() {
        val from = dir("old")
        val to = dir("new")

        val outcome = ProjectMigration.moveAll(from, to, allowTargetNonEmpty = true)

        assertTrue(outcome.nothingToDo)
        assertFalse(from.exists())
    }

    @Test
    fun movingARootIntoItselfIsANoOp() {
        val same = dir("same")
        project(same, "alpha")

        val outcome = ProjectMigration.moveAll(same, same, allowTargetNonEmpty = true)

        assertTrue(outcome.nothingToDo)
        assertTrue(File(same, "alpha").isDirectory)
    }

    // ---- through the store: the user-visible actions ---------------------------

    @Test
    fun theStoreMovesProjectsAndTheActivePointerFollowsThem() {
        val old = dir("old")
        project(old, "gamma")
        val prefs = FakePrefs()
        ProjectStore(old, prefs).select("gamma")   // the user's active project, before the change
        val new = dir("new")

        val store = ProjectStore(new, prefs)
        val outcome = store.moveFrom(old)

        assertEquals(listOf("gamma"), outcome.moved)
        assertEquals(setOf("gamma"), store.projects().map { it.name }.toSet())
        assertEquals("gamma", store.activeName())
        assertEquals(File(new, "gamma").absolutePath, store.active()?.path)
    }

    @Test
    fun theAutomaticMigrationTakesTheNewestLegacyRootThatStillHasProjects() {
        val internal = dir("internal")
        project(internal, "ancient")
        val appExternal = dir("app-external")
        project(appExternal, "recent")
        val current = dir("current")

        // Same order ProjectStore.get() builds: newest location first.
        val store = ProjectStore(current, FakePrefs(), legacyRoots = listOf(appExternal, internal))
        assertEquals(1, store.ensureMigrated())

        assertEquals(setOf("recent"), store.projects().map { it.name }.toSet())
        assertTrue("the older root is left for an explicit move", File(internal, "ancient").isDirectory)
    }

    @Test
    fun theAutomaticMigrationDoesNothingWhenTheCurrentRootAlreadyHasProjects() {
        val legacy = dir("legacy")
        project(legacy, "old-work")
        val current = dir("current")
        project(current, "new-work")

        val store = ProjectStore(current, FakePrefs(), legacyRoots = listOf(legacy))

        assertEquals(0, store.ensureMigrated())
        assertEquals(listOf("new-work"), store.projects().map { it.name })
        assertTrue(File(legacy, "old-work").isDirectory)
    }

    @Test
    fun aRootThatIsAlsoALegacyCandidateIsNeverMovedOntoItself() {
        val root = dir("root")
        project(root, "alpha")

        val store = ProjectStore(root, FakePrefs(), legacyRoots = listOf(root))
        assertEquals(0, store.ensureMigrated())
        assertEquals(listOf("alpha"), store.projects().map { it.name })
        assertTrue("the project is still where it was", File(root, "alpha").isDirectory)
    }

    private class FakePrefs : SharedPreferences {
        private val data = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            private val removed = HashSet<String>()

            // Written out longhand on purpose: a trailing-lambda `apply { ... }` inside
            // a class that also HAS an `apply()` member reads as the SharedPreferences
            // commit, which is not what any of these methods mean.
            private fun stage(key: String, value: Any?): SharedPreferences.Editor {
                staged[key] = value
                return this
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor = stage(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = stage(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)
            override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
            override fun clear(): SharedPreferences.Editor { data.clear(); return this }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() = flush()

            private fun flush() {
                removed.forEach { data.remove(it) }
                data.putAll(staged)
                staged.clear()
                removed.clear()
            }
        }
    }
}
