package ai.opencode.android.projects

import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.memory.ProjectMemory
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.Secrets
import ai.opencode.android.security.SecretStore
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 on-device gates: the workspace boundary and the memory layer, proven
 * against the real app filesystem and the real OpenCode server - not by looking
 * at the UI.
 *
 * W1 and W3 are model-free and deterministic on purpose: the Phase 6 live-tool
 * gate (L2) showed that a model choosing a tool is not something to bet a
 * phase's acceptance on. Isolation (W2) is asserted through OpenCode's own file
 * layer - the same instance-directory scoping the agent's read/list tools use:
 *
 *  * two project directories are mutually invisible to each other's instance
 *    (`GET /file?path=` returns only the project's own tree), and
 *  * a read that would escape the project directory is refused by the server
 *    (`GET /file/content` dies with "Path escapes the location" - upstream's
 *    `handlers/file.ts` guard, `FSUtil.contains(directory, file)`, which returns
 *    a 500 over HTTP).
 *
 * W3 asserts the exact files OpenCode loads (`AGENTS.md` in the project root and
 * in the global config dir) exist where the app writes them, round-trip, are
 * editable and removable - inspectability and removability on the device.
 *
 * Verdict discipline matches Phase 5/6: every gate prints `P7_<id> PASS|FAIL|SKIP
 * :: detail` to logcat + stdout, so the host-side collector can count exactly what
 * ran. W2 SKIPs (never silently passes) when the app-owned server is not up.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceIsolationGatesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val paths = RuntimePaths.get(context)
    private val base = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}"

    private fun password(): String {
        val pw = SecretStore.get(context).get(Secrets.SERVER_PASSWORD)
        assertNotNull("no Keystore-held server password", pw)
        return pw!!
    }

    private fun api(directory: String): OpenCodeApi =
        OpenCodeApi(base, RuntimeEnv.SERVER_USER, password(), directory = directory)

    private fun serverUp(): Boolean =
        runCatching { api(paths.workspaces.absolutePath).health().optBoolean("healthy") }.getOrDefault(false)

    /** Start the runtime if needed and wait for it to answer /global/health. */
    private fun ensureServer(): Boolean {
        val mgr = runCatching { RuntimeManager.get(context) }
        if (mgr.isSuccess) runCatching { mgr.getOrThrow().start() }
        if (serverUp()) return true
        val deadline = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < deadline && !serverUp()) Thread.sleep(2000)
        return serverUp()
    }

    /**
     * Verdict sinks, mirroring [ai.opencode.android.ui.UiGateSupport]: `println`
     * from an instrumented test is redirected to logcat and never reaches
     * `am instrument`'s result stream, and the logcat ring buffer a live runtime
     * writes to can rotate a verdict off before the harness reads it. Each line
     * therefore also lands in a file inside the app's own storage, which the host
     * script reads deterministically via `run-as`.
     */
    private val verdictSinks: List<File> by lazy {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        listOfNotNull(
            ctx.getExternalFilesDir(null)?.let { File(it, "p7-verdicts.txt") },
            File(ctx.filesDir, "p7-verdicts.txt"),
        )
    }

    private fun emit(id: String, verdict: String, detail: String) {
        val line = "P7_$id $verdict :: $detail"
        Log.i("OpenCode/gate", line)
        println(line)
        verdictSinks.forEach { sink -> runCatching { sink.appendText(line + "\n") } }
    }

    private fun gate(id: String, ok: Boolean, detail: String) {
        emit(id, if (ok) "PASS" else "FAIL", detail)
        assertTrue("P7_$id", ok)
    }

    private fun skip(id: String, detail: String) {
        emit(id, "SKIP", detail)
        Assume.assumeTrue("skipped: $detail", false)
    }

    // ---- W1: the project lifecycle works on the real filesystem -------------

    @Test
    fun w1_projectLifecycleCreateRenameDeleteAdopt() {
        val store = ProjectStore.get(context)
        val stamp = System.currentTimeMillis()
        val a = store.create("p7-life-$stamp")
        assertTrue(File(store.rootPath(), a.name).isDirectory)

        val renamed = store.rename(a.name, "p7-renamed-$stamp")
        assertNotNull("rename failed", renamed)
        assertTrue("renamed dir missing", File(store.rootPath(), renamed!!.name).isDirectory)
        assertTrue("old dir still present", !File(store.rootPath(), a.name).exists())

        // adopt: register a directory the importer would have copied into place.
        val adoptedDir = File(store.rootPath(), "p7-adopted-$stamp")
        adoptedDir.mkdirs()
        File(adoptedDir, "kept.txt").writeText("kept")
        val adopted = store.adopt(adoptedDir.name)
        assertTrue(File(adoptedDir, "kept.txt").isFile)

        val deleted = store.delete(renamed.name)
        assertTrue("delete reported failure", deleted)
        assertTrue("deleted dir still present", !File(store.rootPath(), renamed.name).exists())

        // Clean up the adopted project so the device is left tidy.
        store.delete(adopted.name)

        gate(
            "W1_PROJECT_LIFECYCLE",
            true,
            "created=${a.name} renamed=${renamed.name} adopted=${adopted.name} delete=$deleted",
        )
    }

    // ---- W2: the workspace boundary is real, at the server's file layer -----

    @Test
    fun w2_fileAccessIsScopedToTheProjectDirectory() {
        if (!ensureServer()) skip("W2_WORKSPACE_ISOLATION", "app-owned OpenCode server not answering /global/health")

        val stamp = System.currentTimeMillis()
        val dirA = File(paths.workspaces, "p7-iso-a-$stamp").apply { mkdirs() }
        val dirB = File(paths.workspaces, "p7-iso-b-$stamp").apply { mkdirs() }
        val markerA = "P7_INSIDE_A_$stamp"
        val markerB = "P7_INSIDE_B_$stamp"
        val outsideMarker = "P7_OUTSIDE_$stamp"
        val insideA = File(dirA, "inside-a.txt").apply { writeText(markerA) }
        val insideB = File(dirB, "inside-b.txt").apply { writeText(markerB) }
        // A file OUTSIDE any project directory: the parent of the workspaces root
        // (app filesDir). It exists and the OS can read it - only the server must
        // refuse it for an instance whose directory is dirA.
        val outside = File(paths.workspaces.parentFile, "p7-outside.txt").apply { writeText(outsideMarker) }

        try {
            val apiA = api(dirA.absolutePath)
            val apiB = api(dirB.absolutePath)

            // 1. Listing A shows A's file and neither B's nor the outside file.
            val listA = apiA.fileList("")
            val namesA = listA.map { it.path }.toSet()
            val seesOwn = listA.any { it.path.endsWith("inside-a.txt") }
            val seesOtherProject = namesA.any { it.contains("inside-b.txt") }
            val seesOutside = namesA.any { it.contains("p7-outside.txt") }

            // 2. Reading a file through the project directory works for A...
            val readOwn = runCatching { apiA.fileContent("inside-a.txt") }
                .getOrNull()?.contains(markerA) == true

            // 3. ...and the same relative name resolves into B's own tree for B.
            val readOwnB = runCatching { apiB.fileContent("inside-b.txt") }
                .getOrNull()?.contains(markerB) == true

            // 4. A read that escapes the project directory is refused by the server.
            //    `../../<outside>` resolves to filesDir (dirA's grandparent); upstream's
            //    `FSUtil.contains(directory, file)` guard must refuse it. The verdict is
            //    "refused" (any non-2xx); the exact status and upstream's own words
            //    ("Path escapes the location") land in the detail line.
            val escape = runCatching { apiA.fileContent("../../p7-outside.txt") }
            val escapeExc = escape.exceptionOrNull() as? OpenCodeApi.ApiException
            val escapeRefused = escapeExc != null && escapeExc.status !in 200..299
            val escapeBody = escapeExc?.body.orEmpty()

            val ok = seesOwn && !seesOtherProject && !seesOutside && readOwn && readOwnB && escapeRefused
            gate(
                "W2_WORKSPACE_ISOLATION",
                ok,
                "listA=${namesA.size} seesOwn=$seesOwn seesOtherProject=$seesOtherProject seesOutside=$seesOutside " +
                    "readOwnA=$readOwn readOwnB=$readOwnB escapeRefused=$escapeRefused " +
                    "escapeBody='${escapeBody.take(120)}'",
            )
        } finally {
            runCatching { insideA.delete() }
            runCatching { insideB.delete() }
            runCatching { dirA.delete() }
            runCatching { dirB.delete() }
            runCatching { outside.delete() }
        }
    }

    // ---- W3: memory is inspectable, editable and removable on the device ----

    @Test
    fun w3_memoryFilesAreInspectableEditableAndRemovable() {
        val stamp = System.currentTimeMillis()
        val store = ProjectStore.get(context)
        val project = store.create("p7-memory-$stamp")
        val memory = ProjectMemory(
            workspacesRoot = File(store.rootPath()),
            globalRulesDir = paths.xdgConfigOpencode,
        )
        val projectText = "Always answer in one sentence. Marker $stamp"
        val globalText = "Global rule marker $stamp"

        try {
            // write -> the exact file OpenCode loads for the project scope
            assertTrue(memory.writeProject(project.name, projectText))
            val projectFile = memory.projectFile(project.name)
            assertTrue("project AGENTS.md missing", projectFile.isFile)
            assertTrue("project rules did not round-trip", memory.readProject(project.name).contains("$stamp"))

            // write -> the exact file OpenCode loads for the global scope
            assertTrue(memory.writeGlobal(globalText))
            assertTrue("global AGENTS.md missing", memory.globalFile().isFile)
            assertTrue("global rules did not round-trip", memory.readGlobal().contains("$stamp"))

            // editable: rewrite in place
            assertTrue(memory.writeProject(project.name, "replacement $stamp"))
            assertTrue(memory.readProject(project.name).contains("replacement"))

            // removable: delete, file is gone
            assertTrue(memory.removeProject(project.name))
            assertTrue("project AGENTS.md still present", !memory.projectFile(project.name).exists())
            assertTrue(memory.removeGlobal())
            assertTrue("global AGENTS.md still present", !memory.globalFile().exists())

            gate(
                "W3_MEMORY_INSPECTABLE_REMOVABLE",
                true,
                "projectFile=${projectFile.absolutePath} globalFile=${memory.globalFile().absolutePath}",
            )
        } finally {
            store.delete(project.name)
        }
    }
}
