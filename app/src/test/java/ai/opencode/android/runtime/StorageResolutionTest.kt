package ai.opencode.android.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 10 continuation v3: where the projects live, as a decision table.
 *
 * The product promise is that a project is an ordinary, user-visible folder from
 * the moment it is created. That promise rests on this resolution order, and the
 * branches that matter are the awkward ones:
 *
 *  * All files access granted -> `Documents/OpenCode` on shared storage, and the
 *    app may claim a file manager can open it;
 *  * granted but the user chose their own folder -> the chosen folder wins;
 *  * NOT granted -> the app-specific external directory, which `adb` can read but
 *    a file manager cannot on Android 11+, and `fileManagerVisible` must be false
 *    so the UI cannot claim otherwise;
 *  * Android 10 (API 29) -> the same fallback IS file-manager visible, because the
 *    platform still allowed browsing `Android/data` then;
 *  * no usable external storage at all -> app-private storage, invisible to
 *    everything, and again labelled as such.
 *
 * The other half is the SAF folder the user may pick. Only a folder on the primary
 * volume can become the live root (the runtime needs a POSIX path), and the id
 * arrives from the system picker into code that turns it into a shell working
 * directory, so traversal and NUL are refused rather than sanitised.
 */
class StorageResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesDir() = tmp.newFolder("files")
    private fun externalDir() = tmp.newFolder("external")
    private fun sharedVolume() = tmp.newFolder("shared")

    @Test
    fun allFilesAccessMakesSharedStorageTheDefault() {
        val files = filesDir()
        val shared = sharedVolume()
        val external = externalDir()
        val paths = RuntimePaths.forTesting(
            filesDir = files,
            nativeLibraryDir = File(files, "lib"),
            externalFilesDir = external,
            publicStorageRoot = shared,
            publicStorageUsable = true,
            apiLevel = 34,
        )

        assertEquals(StorageMode.PUBLIC, paths.mode)
        assertEquals(File(shared, RuntimePaths.PUBLIC_PROJECTS_DIR).absolutePath, paths.workspaces.absolutePath)
        assertEquals("Documents/OpenCode", RuntimePaths.PUBLIC_PROJECTS_DIR)
        assertTrue("a file manager must be able to open the shipping default", paths.fileManagerVisible)
        assertTrue(paths.workspacesVisibleToShell)
        assertTrue(paths.workspacesAreExternal)
        // The fallback and the migration source are still distinct locations.
        assertFalse(paths.workspaces.absolutePath == paths.internalWorkspaces.absolutePath)
        assertEquals(
            File(external, "workspaces").absolutePath,
            paths.externalWorkspaces?.absolutePath,
        )
    }

    @Test
    fun aChosenFolderBeatsTheSharedDefault() {
        val files = filesDir()
        val chosen = tmp.newFolder("chosen")
        val paths = RuntimePaths.forTesting(
            filesDir = files,
            nativeLibraryDir = File(files, "lib"),
            externalFilesDir = externalDir(),
            overrideRoot = chosen,
            publicStorageRoot = sharedVolume(),
            publicStorageUsable = true,
            apiLevel = 34,
        )

        assertEquals(StorageMode.CHOSEN, paths.mode)
        assertEquals(chosen.absolutePath, paths.workspaces.absolutePath)
        assertTrue(paths.fileManagerVisible)
    }

    @Test
    fun withoutTheGrantTheFallbackIsUsedAndLabelledHonestly() {
        val files = filesDir()
        val external = externalDir()
        val paths = RuntimePaths.forTesting(
            filesDir = files,
            nativeLibraryDir = File(files, "lib"),
            externalFilesDir = external,
            publicStorageRoot = sharedVolume(),
            publicStorageUsable = false,
            apiLevel = 34,
        )

        assertEquals(StorageMode.APP_EXTERNAL, paths.mode)
        assertEquals(File(external, "workspaces").absolutePath, paths.workspaces.absolutePath)
        // The point of the whole v3 change: this location is NOT good enough, and
        // the app has to say so rather than print a reassuring path.
        assertFalse("Android 11+ blocks file managers from Android/data", paths.fileManagerVisible)
        assertTrue("adb and MTP can still reach it", paths.workspacesVisibleToShell)
    }

    @Test
    fun onAndroid10TheAppExternalFallbackIsFileManagerVisible() {
        val files = filesDir()
        val paths = RuntimePaths.forTesting(
            filesDir = files,
            nativeLibraryDir = File(files, "lib"),
            externalFilesDir = externalDir(),
            publicStorageRoot = sharedVolume(),
            publicStorageUsable = false,
            apiLevel = 29,
        )

        assertEquals(StorageMode.APP_EXTERNAL, paths.mode)
        assertTrue("Android 10 still let file managers browse Android/data", paths.fileManagerVisible)
    }

    @Test
    fun withNoUsableExternalStorageTheAppPrivateRootIsTheLastResort() {
        val files = filesDir()
        val paths = RuntimePaths.forTesting(filesDir = files, nativeLibraryDir = File(files, "lib"))

        assertEquals(StorageMode.INTERNAL, paths.mode)
        assertEquals(File(files, "workspaces").absolutePath, paths.workspaces.absolutePath)
        assertFalse(paths.fileManagerVisible)
        assertFalse(paths.workspacesVisibleToShell)
        assertFalse(paths.workspacesAreExternal)
    }

    @Test
    fun aGrantedButUnmountedVolumeFallsBackInsteadOfPretending() {
        val files = filesDir()
        val paths = RuntimePaths.forTesting(
            filesDir = files,
            nativeLibraryDir = File(files, "lib"),
            externalFilesDir = null,
            publicStorageRoot = null,
            publicStorageUsable = true,
            apiLevel = 34,
        )

        // The grant alone proves nothing about the volume being mounted.
        assertEquals(StorageMode.INTERNAL, paths.mode)
        assertFalse(paths.fileManagerVisible)
    }

    // ---- the folder the user picks through SAF --------------------------------

    @Test
    fun aPrimaryVolumeFolderResolvesToARealPath() {
        val primary = sharedVolume()
        val resolved = StorageChoice.realPathOfDocumentId("primary:Documents/Dev", primary)
        assertEquals(File(primary, "Documents/Dev").absolutePath, resolved?.absolutePath)
    }

    @Test
    fun sdCardAndCloudFoldersAreRefusedRatherThanAcceptedAndBroken() {
        val primary = sharedVolume()
        assertNull("an SD card id has no path the runtime can chdir into", StorageChoice.realPathOfDocumentId("1A2B-3C4D:Dev", primary))
        assertNull("the volume root itself is not a project root", StorageChoice.realPathOfDocumentId("primary:", primary))
        assertNull(StorageChoice.realPathOfDocumentId("", primary))
    }

    @Test
    fun traversalInAChosenFolderIdIsRefused() {
        val primary = sharedVolume()
        assertNull(StorageChoice.realPathOfDocumentId("primary:../../data/data/ai.opencode.android/files", primary))
        assertNull(StorageChoice.realPathOfDocumentId("primary:Documents/../../etc", primary))
    }

    @Test
    fun aUsableRootIsOneTheAppCanActuallyWriteIn() {
        val dir = tmp.newFolder("root")
        assertTrue(StorageChoice.isUsableRoot(dir))
        // The probe file must not be left behind.
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertTrue("a missing directory is created", StorageChoice.isUsableRoot(File(dir, "new/nested")))
    }
}
