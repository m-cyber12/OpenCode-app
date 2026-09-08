package ai.opencode.android.projects

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Session/persistence (Phase 8 test-matrix item: "session persistence").
 *
 * A "restart" is modelled exactly the way Android does it: the [ProjectStore]
 * object is thrown away and a NEW one is built over the SAME storage (root
 * directory + the same [SharedPreferences] instance, which is where Android
 * would have loaded it from again). What must survive: the project list
 * (discovered from the filesystem), which project the user last opened
* (the "active" pointer), and each project's created/opened timestamps
 * (the "created:<name>" / "opened:<name>" preference keys).
 *
 * The server-side session scoping (sessions keyed by project directory) is
 * exercised on the device by the gate suite; this file proves the app's own
 * bookkeeping survives a process boundary on the JVM.
 */
class ProjectStorePersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * The same two storages survive the "restart"; only the store object dies.
     * The root is created in @Before: TemporaryFolder.root is only valid
     * between the rule's before() and after().
     */
    private lateinit var root: File
    private val prefs = FakePrefs()

    @Before
    fun setUp() {
        root = File(tmp.root, "workspaces").apply { mkdirs() }
    }

    private fun freshStore(): ProjectStore = ProjectStore(root, prefs)

    @Test
    fun theActiveProjectSurvivesARestart() {
        val first = freshStore()
        first.create("alpha")
        first.create("beta")
        first.select("alpha") // user last opened alpha
        assertEquals("alpha", first.activeName())

        // Restart: new store, same storage.
        val second = freshStore()
        assertEquals("alpha", second.activeName())
        val active = second.active()
        assertNotNull(active)
        assertEquals(File(root, "alpha").absolutePath, active!!.path)
    }

    @Test
    fun theProjectListSurvivesARestart() {
        val first = freshStore()
        first.create("alpha")
        first.create("beta")
        first.adopt("imported")

        val second = freshStore()
        val names = second.projects().map { it.name }
        assertTrue("alpha missing after restart: $names", names.contains("alpha"))
        assertTrue("beta missing after restart: $names", names.contains("beta"))
        assertTrue("imported missing after restart: $names", names.contains("imported"))
        assertEquals(3, names.size)
    }

    @Test
    fun timestampsSurviveARestart() {
        val first = freshStore()
        val created = first.create("alpha")
        val createdMs = created.createdMs
        val openedMs = created.lastOpenedMs
        assertTrue(createdMs > 0)
        assertTrue(openedMs > 0)

        val second = freshStore()
        val restored = second.projects().first { it.name == "alpha" }
        assertEquals("created:<name> must survive", createdMs, restored.createdMs)
        assertEquals("opened:<name> must survive", openedMs, restored.lastOpenedMs)
    }

    @Test
    fun aStaleActivePointerFallsBackToTheMostRecentlyOpenedProject() {
        val first = freshStore()
        first.create("alpha")
        first.create("beta")
        first.select("alpha")

        // External deletion: the active project's directory is gone from under
        // the app (user storage management, factory-rescue of one folder). The
        // pointer is now stale; a restart must still yield a usable project
        // rather than crash or open nothing.
        File(root, "alpha").deleteRecursively()

        val second = freshStore()
        val active = second.active()
        assertNotNull("a fallback project must be active", active)
        assertEquals("the surviving, most-recently opened project must win", "beta", active!!.name)
    }

    @Test
    fun aStaleActivePointerWithNoProjectsLeftIsNull() {
        val first = freshStore()
        first.create("only")
        File(root, "only").deleteRecursively()

        val second = freshStore()
        assertNull(second.active())
        assertEquals(0, second.projects().size)
    }

    @Test
    fun filesystemDiscoveredProjectsWithoutPreferencesStillAppear() {
        // A directory placed under the root without any preference history
        // (Phase 5's single "mobile" workspace, or a directory the user created
        // with file tools) must not be orphaned.
        val dir = File(root, "legacy")
        dir.mkdirs()
        File(dir, "note.txt").writeText("left by a previous install")

        val store = freshStore()
        val legacy = store.projects().firstOrNull { it.name == "legacy" }
        assertNotNull(legacy)
        assertEquals(0L, legacy!!.lastOpenedMs)
        assertTrue("createdMs falls back to the directory mtime", legacy.createdMs > 0)
    }

    @Test
    fun theMostRecentlyOpenedProjectSortsFirst() {
        val first = freshStore()
        first.create("old")
        first.create("newer")
        Thread.sleep(5) // make the timestamps distinct
        first.select("newer")

        val second = freshStore()
        val sorted = second.projects().map { it.name }
        assertEquals("newer must sort first after a restart", "newer", sorted.first())
    }

    // ---- minimal in-memory SharedPreferences (same contract as the lifecycle test)

    private class FakePrefs : SharedPreferences {
        private val data = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key) && data[key] != null
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { staged.clear() }
            override fun commit(): Boolean {
                for ((k, v) in staged) { if (v == null) data.remove(k) else data[k] = v }
                staged.clear()
                return true
            }
            override fun apply() {
                for ((k, v) in staged) { if (v == null) data.remove(k) else data[k] = v }
                staged.clear()
            }
        }
    }
}
