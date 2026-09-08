package ai.opencode.android.projects

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the Phase 7 project lifecycle (rename, delete, adopt, unique
 * names) using a real filesystem and an in-memory [SharedPreferences]. The
 * Android framework is not required: [SharedPreferences] is an interface, so the
 * fake below implements the tiny subset the store actually uses.
 */
class ProjectStoreLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ProjectStore = ProjectStore(tmp.newFolder("workspaces"), FakePrefs())

    @Test
    fun createSuffixesCollisions() {
        val s = store()
        val a = s.create("app")
        val b = s.create("app")
        assertEquals("app", a.name)
        assertEquals("app-2", b.name)
    }

    @Test
    fun uniqueNameNeverCollidesWithExistingDirectories() {
        val s = store()
        s.create("demo")
        s.create("demo-2")
        assertEquals("demo-3", s.uniqueName("demo"))
        assertEquals("fresh", s.uniqueName("fresh"))
    }

    @Test
    fun renameMovesTheDirectoryAndKeepsItOpen() {
        val s = store()
        val p = s.create("old")
        s.select("old")
        assertTrue(s.activeName() == "old")

        val renamed = s.rename("old", "new")
        assertNotNull(renamed)
        assertEquals("new", renamed!!.name)
        assertTrue(File(s.rootPath(), "new").isDirectory)
        assertFalse(File(s.rootPath(), "old").exists())
        // The active pointer followed the rename.
        assertEquals("new", s.activeName())
    }

    @Test
    fun renameToACollidingNameGetsASuffix() {
        val s = store()
        s.create("a")
        s.create("b")
        val renamed = s.rename("a", "b")
        assertNotNull(renamed)
        assertTrue(renamed!!.name.startsWith("b-"))
    }

    @Test
    fun renameOfAMissingProjectReturnsNull() {
        val s = store()
        assertNull(s.rename("nope", "whatever"))
    }

    @Test
    fun deleteRemovesTheDirectoryAndClearsActiveWhenItWasCurrent() {
        val s = store()
        val p = s.create("gone")
        s.select("gone")
        assertTrue(s.delete("gone"))
        assertFalse(File(s.rootPath(), "gone").exists())
        assertTrue(s.activeName().isEmpty())
        // The project is gone from the list.
        assertFalse(s.projects().any { it.name == "gone" })
    }

    @Test
    fun adoptRegistersAnExistingDirectoryWithoutRenamingIt() {
        val s = store()
        val dir = File(s.rootPath(), "imported")
        dir.mkdirs()
        File(dir, "f.txt").writeText("x")

        val adopted = s.adopt("imported")
        assertEquals("imported", adopted.name)
        assertTrue(File(dir, "f.txt").isFile)
        assertTrue(s.projects().any { it.name == "imported" })
    }

    // ---- minimal in-memory SharedPreferences ---------------------------------

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
            override fun commit(): Boolean { data.putAll(staged); return true }
            override fun apply() { data.putAll(staged) }
        }
    }
}
