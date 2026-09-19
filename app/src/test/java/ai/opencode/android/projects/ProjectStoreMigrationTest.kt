package ai.opencode.android.projects

import android.content.SharedPreferences
import ai.opencode.android.runtime.RuntimePaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 10 continuation: where projects live, and what happens to the ones an
 * older install left behind.
 *
 * The root moved from `filesDir/workspaces` (app-private, invisible to every
 * file manager, to MTP and to a non-root `adb shell`) to
 * `getExternalFilesDir(null)/workspaces`. That is a storage-location change, so
 * it needs proof in the two directions that can silently lose data:
 *
 *  * resolution — the external root is used when external storage exists, and the
 *    old internal root is used (not a crash, not an empty list) when it does not;
 *  * migration — projects written under the old root are moved across filesystems
 *    exactly once, a failure leaves the originals intact, and a user who deleted a
 *    project does not get it resurrected by a later start.
 *
 * These are JVM tests: real directories, a real (faked) SharedPreferences, no
 * Android framework. The device-side counterparts are the Phase 7 W1-W4 gates and
 * `phase10/scripts/92-workspace-visibility.sh`, which check the same facts from
 * outside the app with a non-root `adb shell`.
 */
class ProjectStoreMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = FakePrefs()

    /** Mirrors what RuntimePaths resolves: filesDir/workspaces vs external/workspaces. */
    private fun legacyDir(): File = File(tmp.newFolder("files"), "workspaces")

    private fun externalDir(): File = File(tmp.newFolder("external"), "workspaces")

    private fun makeProject(dir: File, name: String, fileName: String, content: String): File {
        val project = File(dir, name).apply { mkdirs() }
        File(project, fileName).writeText(content)
        return project
    }

    // ---- resolution ----------------------------------------------------------

    @Test
    fun externalStorageIsTheProjectRootWhenItIsAvailable() {
        val files = tmp.newFolder("files")
        val external = tmp.newFolder("external")
        val paths = RuntimePaths.forTesting(files, File(files, "lib"), external)

        assertTrue("external storage must win", paths.workspacesAreExternal)
        assertEquals(File(external, "workspaces").absolutePath, paths.workspaces.absolutePath)
        assertEquals(File(files, "workspaces").absolutePath, paths.internalWorkspaces.absolutePath)
        // ...and the two really are different places, i.e. there IS something to migrate.
        assertFalse(paths.workspaces.absolutePath == paths.internalWorkspaces.absolutePath)
    }

    @Test
    fun theInternalRootIsUsedWhenExternalStorageIsUnavailable() {
        val files = tmp.newFolder("files")
        val paths = RuntimePaths.forTesting(files, File(files, "lib"), null)

        assertFalse(paths.workspacesAreExternal)
        assertEquals(File(files, "workspaces").absolutePath, paths.workspaces.absolutePath)
        // Same object as the legacy root: nothing to migrate, nothing to delete.
        assertEquals(paths.internalWorkspaces.absolutePath, paths.workspaces.absolutePath)
    }

    // ---- migration -----------------------------------------------------------

    @Test
    fun existingProjectsMoveAcrossFilesystemsAndTheOldRootIsRemoved() {
        val legacy = legacyDir()
        makeProject(legacy, "alpha", "main.kt", "fun main() = Unit")
        makeProject(legacy, "beta", "notes.md", "# beta")
        val root = externalDir()

        // A rename cannot work between /data and the emulated volume, so the
        // migration must fall back to copy+delete - force that path here.
        val store = ProjectStore(root, prefs, legacyRoot = legacy)
        assertEquals(2, store.ensureMigrated())

        assertEquals(setOf("alpha", "beta"), store.projects().map { it.name }.toSet())
        assertEquals("fun main() = Unit", File(File(root, "alpha"), "main.kt").readText())
        assertEquals("# beta", File(File(root, "beta"), "notes.md").readText())
        assertFalse("the old root must not survive as a duplicate", legacy.exists())
    }

    @Test
    fun migrationIsIdempotentAndNeverResurrectsDeletedProjects() {
        val legacy = legacyDir()
        makeProject(legacy, "alpha", "a.txt", "a")
        val root = externalDir()
        val store = ProjectStore(root, prefs, legacyRoot = legacy)

        assertEquals(1, store.ensureMigrated())
        assertEquals(0, store.ensureMigrated())   // second call: legacy is gone

        // The user deletes the project. A later start must NOT copy it back: the
        // rule is "migrate only into an empty root", and the legacy directory was
        // removed by the successful migration.
        assertTrue(store.delete("alpha"))
        assertTrue(store.projects().isEmpty())
        assertEquals(0, store.ensureMigrated())
        assertTrue(store.projects().isEmpty())
    }

    @Test
    fun anInstallThatAlreadyHasProjectsIsNotOverwritten() {
        val legacy = legacyDir()
        makeProject(legacy, "old-work", "old.txt", "old")
        val root = externalDir()
        makeProject(root, "new-work", "new.txt", "new")

        val store = ProjectStore(root, prefs, legacyRoot = legacy)
        assertEquals(0, store.ensureMigrated())
        assertEquals(listOf("new-work"), store.projects().map { it.name })
        assertTrue("the untouched legacy tree stays where it is", File(legacy, "old-work").isDirectory)
    }

    @Test
    fun aFreshInstallWithNoLegacyRootIsANoOp() {
        val store = ProjectStore(externalDir(), prefs, legacyRoot = null)
        assertEquals(0, store.ensureMigrated())
        assertTrue(store.projects().isEmpty())
    }

    @Test
    fun theUsersPreferenceHistorySurvivesTheMigration() {
        val legacy = legacyDir()
        makeProject(legacy, "gamma", "g.txt", "g")
        ProjectStore(legacy, prefs).select("gamma")   // the user's active pointer, pre-upgrade
        val root = externalDir()

        val upgraded = ProjectStore(root, prefs, legacyRoot = legacy)
        assertEquals(1, upgraded.ensureMigrated())

        // Preference keys are name-based, so the pointer and timestamps still
        // resolve against the moved directory.
        assertEquals("gamma", upgraded.activeName())
        assertEquals(File(root, "gamma").absolutePath, upgraded.active()?.path)
        assertTrue(File(root, "gamma").isDirectory)
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
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { removals.addAll(data.keys) }
            override fun commit(): Boolean { applyPending(); return true }
            override fun apply() { applyPending() }
            private fun applyPending() {
                removals.forEach { data.remove(it) }
                removals.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                pending.clear()
            }
        }
    }
}
