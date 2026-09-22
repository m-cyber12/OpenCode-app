package ai.opencode.android.projects

import ai.opencode.android.client.LoopbackGuard
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.memory.ProjectMemory
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimeManager
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.runtime.StorageChoice
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
 * v4 adds W5: the same boundary at the depth the product uses (a workspace folder
 * with sibling project subfolders, and a chat that must not create a folder), and
 * W6: switching the workspace hides the old projects without deleting anything, and
 * the Settings screen's own "Move them here" brings them over.
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

    // ---- W5: the v4 hierarchy - one workspace, sibling projects, chats inside ----

    /**
     * Phase 10 continuation v4, item 1: the model is workspace -> project -> chat,
     * where the project is a direct subfolder of the workspace and a chat is an
     * OpenCode session inside that project ("no folder of its own").
     *
     * W2 already proves the boundary at one level (two projects that are siblings
     * under the app's root). This gate proves it at the DEPTH THE PRODUCT USES:
     * a workspace folder holding `1` and `2`, an instance scoped to `1`, and the
     * three escapes a file manager performs by hand - `../2/...` into the sibling
     * project, `../workspace-level.txt` into the workspace root itself, and `../../`
     * out of the workspace entirely. All three must be refused by the server, while
     * each project still reads its own file with a plain relative name: the boundary
     * is the PROJECT folder, not the shared workspace root.
     *
     * The chat half is asserted the same way - by observing the filesystem and the
     * server's own session list, not by reading the app's code: creating a session
     * in project `1` must add no directory anywhere, and the new session must be
     * visible to project `1`'s instance and invisible to project `2`'s.
     */
    @Test
    fun w5_siblingProjectsUnderOneWorkspaceStayConfined() {
        if (!ensureServer()) skip("W5_SIBLING_PROJECT_CONFINEMENT", "app-owned OpenCode server not answering /global/health")

        val stamp = System.currentTimeMillis()
        val wsRoot = File(paths.workspaces, "p10ws-$stamp").apply { mkdirs() }
        val p1 = File(wsRoot, "1").apply { mkdirs() }
        val p2 = File(wsRoot, "2").apply { mkdirs() }
        val marker1 = "P10_WS1_$stamp"
        val marker2 = "P10_WS2_$stamp"
        val one = File(p1, "one.txt").apply { writeText(marker1) }
        val two = File(p2, "two.txt").apply { writeText(marker2) }
        val workspaceLevel = File(wsRoot, "workspace-level.txt").apply { writeText("P10_ROOT_$stamp") }
        var createdSession: String? = null

        try {
            val api1 = api(p1.absolutePath)
            val api2 = api(p2.absolutePath)

            // 1. A project sees its own file, not the sibling's, not the workspace's.
            val names1 = api1.fileList("").map { it.path }.toSet()
            val seesOwn = names1.any { it.endsWith("one.txt") }
            val seesSiblingProject = names1.any { it.contains("two.txt") }
            val seesWorkspaceRoot = names1.any { it.contains("workspace-level.txt") }

            // 2. Three escapes, all refused. The status and upstream's own words go
            //    into the detail line, so "refused" is auditable rather than asserted.
            fun refusal(path: String): Pair<Boolean, String> {
                val result = runCatching { api1.fileContent(path) }
                val exc = result.exceptionOrNull() as? OpenCodeApi.ApiException
                return (exc != null && exc.status !in 200..299) to (exc?.body.orEmpty())
            }
            val (siblingRefused, siblingBody) = refusal("../2/two.txt")
            val (workspaceRefused, workspaceBody) = refusal("../workspace-level.txt")
            val (deepRefused, deepBody) = refusal("../../p10-nowhere-$stamp.txt")

            // 3. Each project reads its own file with a relative name: the boundary is
            //    the project folder, so a project is usable on its own.
            val readOwn1 = runCatching { api1.fileContent("one.txt") }.getOrNull()?.contains(marker1) == true
            val readOwn2 = runCatching { api2.fileContent("two.txt") }.getOrNull()?.contains(marker2) == true

            // 4. A chat is a session in the project directory and creates no folder.
            val filesBefore = p1.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val session = runCatching { api1.createSession("v4-w5-$stamp") }.getOrNull()
            createdSession = session?.id
            val filesAfter = p1.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val noFolderForChat = filesAfter == filesBefore
            val workspaceChildren = wsRoot.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val workspaceShape = workspaceChildren == listOf("1", "2", "workspace-level.txt")

            // 5. The chat belongs to its project: project 1's instance lists it,
            //    project 2's does not.
            val ids1 = runCatching { api1.listSessions(limit = 100) }.getOrDefault(emptyList()).map { it.id }
            val ids2 = runCatching { api2.listSessions(limit = 100) }.getOrDefault(emptyList()).map { it.id }
            val chatOwned = createdSession != null && ids1.contains(createdSession) && !ids2.contains(createdSession)

            val ok = seesOwn && !seesSiblingProject && !seesWorkspaceRoot &&
                siblingRefused && workspaceRefused && deepRefused &&
                readOwn1 && readOwn2 && noFolderForChat && workspaceShape && chatOwned
            gate(
                "W5_SIBLING_PROJECT_CONFINEMENT",
                ok,
                "wsRoot=${wsRoot.absolutePath} project=${p1.name} " +
                    "seesOwn=$seesOwn seesSibling=$seesSiblingProject seesWorkspaceFile=$seesWorkspaceRoot " +
                    "refused=sibling:$siblingRefused,workspace:$workspaceRefused,deep:$deepRefused " +
                    "readOwn=$readOwn1/$readOwn2 chatNoFolder=$noFolderForChat shape=$workspaceShape " +
                    "chatOwned=$chatOwned session=${createdSession.orEmpty()} " +
                    "siblingBody='${siblingBody.take(80)}' workspaceBody='${workspaceBody.take(80)}' " +
                    "deepBody='${deepBody.take(80)}'",
            )
        } finally {
            runCatching { createdSession?.let { api(p1.absolutePath).deleteSession(it) } }
            runCatching { one.delete() }
            runCatching { two.delete() }
            runCatching { workspaceLevel.delete() }
            runCatching { p1.delete() }
            runCatching { p2.delete() }
            runCatching { wsRoot.delete() }
        }
    }

    // ---- W6: switching the workspace hides, and deletes nothing --------------

    /**
     * v4 item 1, the half a listing cannot prove: pointing the app at another folder
     * changes WHICH projects it shows (the terminal-`cd` behaviour the brief asks
     * for) and the ones it stops showing are still on disk, untouched.
     *
     * The switch is driven through [StorageController.switchTo] - the same function
     * the system folder picker calls - rather than by reimplementing it here, so a
     * green verdict is about the app, not about the test's own idea of the app.
     *
     * The round trip matters as much as the first half: the gate then asks for the
     * projects back with the Settings screen's own "Move them here"
     * ([StorageController.moveProjectsIntoPlace]), because "nothing is lost" is only
     * honest if there is a way back. It ends by leaving the device exactly as it found
     * it: the original workspace active, the project back in it, and the temporary
     * root removed.
     */
    @Test
    fun w6_switchingTheWorkspaceHidesTheOldProjectsAndDeletesNothing() {
        // A fresh store per step on purpose: Paths and ProjectStore are cached per
        // context and refresh() replaces the cached instances, so a store captured
        // before a switch would keep answering about the OLD root - the exact bug a
        // gate like this exists to catch, and one it must not have itself.
        fun storeNow(): ProjectStore = ProjectStore.get(context)
        val controller = StorageController.get(context)
        val live = RuntimePaths.get(context).workspaces
        // How the workspace was chosen before the gate ran: restoring the MODE matters
        // as much as restoring the project, or the next gate (and the next app start)
        // would run in a state this gate invented.
        val wasChosen = StorageChoice.chosenRoot(context) != null
        val temp = File(context.getExternalFilesDir(null) ?: context.filesDir, "p10w6-workspace")
        var stamp = "p10w6"
        var n = 2
        while (File(live, stamp).exists()) {
            stamp = "p10w6-$n"
            n += 1
        }
        var created: Project? = null
        try {
            created = storeNow().create(stamp)
            storeNow().select(created.name)
            File(created.dir, "survives.txt").writeText(MARKER)
            val beforeNames = storeNow().projects().map { it.name }
            assertTrue("the project is not in the live workspace before switching", beforeNames.contains(created.name))

            // ---- the switch: a different folder, no migration ----
            temp.mkdirs()
            val switched = controller.switchTo(temp)
            val nowRoot = RuntimePaths.get(context).workspaces
            val afterNames = storeNow().projects().map { it.name }
            val oldDir = File(live, created.name)
            val stillOnDisk = oldDir.isDirectory && File(oldDir, "survives.txt").isFile
            val markerIntact = runCatching { File(oldDir, "survives.txt").readText() }
                .getOrDefault("") == MARKER
            // The old folder is offered back as a pending root - that is the bridge the
            // Settings screen turns into its "Move them here" action.
            val snapshotAfter = controller.snapshot()
            val pending = snapshotAfter.pendingProjects
            val hid = !afterNames.contains(created.name)
            val askedFor = snapshotAfter.pendingRoots.any { it == live.absolutePath }

            // ---- the way back: the user's own "Move them here" ----
            val moved = controller.moveProjectsIntoPlace()
            val backRoot = RuntimePaths.get(context).workspaces
            val backNames = storeNow().projects().map { it.name }
            val contentKept = runCatching { File(backRoot, created.name, "survives.txt").readText() }
                .getOrDefault("") == MARKER

            gate(
                "W6_WORKSPACE_SWITCH_HIDES_AND_DELETES_NOTHING",
                switched.ok && nowRoot.absolutePath == temp.absolutePath && hid && stillOnDisk &&
                    markerIntact && pending >= 1 && askedFor && moved.ok &&
                    backNames.contains(created.name) && contentKept,
                "from=${live.absolutePath} to=${nowRoot.absolutePath} mode=${snapshotAfter.mode} " +
                    "whenSwitched=$afterNames hid=$hid oldStillOnDisk=$stillOnDisk markerIntact=$markerIntact " +
                    "pendingProjects=$pending pendingNamesOldRoot=$askedFor " +
                    "movedBack=${moved.moved} rootNow=${backRoot.absolutePath} namesAfterMove=$backNames " +
                    "contentKept=$contentKept before=$beforeNames",
            )
        } finally {
            // Leave the device as found: the same MODE, the original workspace active,
            // and our project deleted from wherever the round trip left it.
            runCatching { if (wasChosen) controller.switchTo(live) else controller.useDefaultLocation() }
            // Anything still in the temporary root comes home BEFORE the folder is
            // removed: the switch-back above only makes the live root active, and a
            // project left in a folder that is about to be deleted would be quietly
            // lost by the cleanup rather than by the app.
            runCatching { ProjectMigration.moveAll(temp, live, allowTargetNonEmpty = true) }
            runCatching { created?.let { storeNow().delete(it.name) } }
            runCatching { temp.deleteRecursively() }
        }
    }

    private companion object {
        /** Written before the switch and read after the round trip. */
        const val MARKER = "written before the switch"
    }

    // ---- W4: the workspace is OUTSIDE the sandbox, and the server agrees -----

    /**
     * Phase 10 continuation. W1-W3 prove the boundary is enforced; W4 proves the
     * location is not a black box.
     *
     * Two halves, both of which have to hold, and both of which a "the app writes
     * files somewhere" claim can fail:
     *
     *  1. **Where** — the resolved project root is on SHARED storage and outside
     *     `Android/data`, so an ordinary file manager (not just `adb`) can browse
     *     it. Phase 10 continuation v3 changed the answer from "somewhere adb can
     *     read" to "somewhere the Files app can read": the default is
     *     `/storage/emulated/0/Documents/OpenCode`, which needs All files access.
     *     When the grant is absent the app must NOT claim visibility - it falls
     *     back to the app-specific external directory and says so, and this gate
     *     accepts that fallback only while `mode` agrees with it (a mismatch, e.g.
     *     "granted but still writing to Android/data", is a FAIL). The host script
     *     then repeats the claim from outside the app
     *     (`phase10/scripts/92-workspace-visibility.sh`) on the absolute path this
     *     gate prints as `wsRoot=` - an app asserting its own path is not evidence
     *     that anyone else can read it.
     *
     *  2. **Through the server** — a shell command run through OpenCode's own
     *     `/session/:id/shell` endpoint (the same path the agent's shell tool uses)
     *     executes with its working directory inside that root and the file it
     *     writes is readable there. This is model-free and deterministic on
     *     purpose: whether an LLM decides to call a tool is not something to bet a
     *     storage claim on (the Phase 6 lesson).
     *
     * The gate deletes nothing it did not create; the marker file is left in place
     * for the host script to read, then removed by it.
     */
    @Test
    fun w4_workspaceLivesOutsideTheAppSandboxAndTheServerWritesThere() {
        val store = ProjectStore.get(context)
        val root = File(store.rootPath())
        val paths = RuntimePaths.get(context)
        val external = context.getExternalFilesDir(null)
        val internalRoot = File(context.filesDir, "workspaces").absolutePath
        val sharedVolume = android.os.Environment.getExternalStorageDirectory().absolutePath
        val granted = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

        val inExternal = external != null && root.absolutePath.startsWith(external.absolutePath + File.separator)
        val inShared = root.absolutePath.startsWith(sharedVolume + File.separator)
        val underAndroidData = root.absolutePath.contains("/Android/data/")
        val inInternalData = root.absolutePath.startsWith("/data/data/") ||
            root.absolutePath.startsWith("/data/user/0/")

        // The v3 rule, per mode. `mode` is the app's own declaration, so each branch
        // also checks that the declaration matches the path it names - the failure
        // this guards against is an app that grants itself a good-sounding label
        // while writing somewhere else.
        val locationOk = when (paths.mode) {
            // The shipping default: shared storage, outside Android/data, and the
            // app says a file manager can open it.
            ai.opencode.android.runtime.StorageMode.PUBLIC,
            ai.opencode.android.runtime.StorageMode.CHOSEN ->
                inShared && !underAndroidData && !inInternalData && paths.fileManagerVisible
            // The documented fallback (no All files access): adb can reach it, and
            // the app must admit a file manager cannot.
            ai.opencode.android.runtime.StorageMode.APP_EXTERNAL ->
                inExternal && !inInternalData && !paths.fileManagerVisible
            // Last resort on a device with no usable external storage: nothing
            // outside the app can see it and the app has to say so.
            ai.opencode.android.runtime.StorageMode.INTERNAL ->
                inInternalData && !paths.fileManagerVisible
        }
        // A grant that resolves to nothing is a bug, not a configuration.
        val grantHonoured = !granted || paths.mode == ai.opencode.android.runtime.StorageMode.PUBLIC ||
            paths.mode == ai.opencode.android.runtime.StorageMode.CHOSEN

        // Part 2 needs the server; without it the LOCATION half is still a verdict
        // (the location is a filesystem fact), so the gate reports what it saw
        // rather than SKIPping the whole thing.
        val project = store.create("w4-storage-${System.currentTimeMillis()}")
        val dir = File(store.rootPath(), project.name)
        val marker = "P10_VISIBLE_${System.currentTimeMillis()}"
        var serverWrote = false
        var shellOutput = ""
        var serverDetail = "server not answering /global/health"
        try {
            if (ensureServer()) {
                val api = api(dir.absolutePath)
                val sid = api.createSession("w4-visibility").id
                val cmd = "echo $marker > p10-visible.txt && pwd && ls -l p10-visible.txt"
                val status = api.shell(sid, cmd, "build")
                serverDetail = "shellStatus=$status"
                val written = File(dir, "p10-visible.txt")
                serverWrote = written.isFile && written.readText().contains(marker)
                shellOutput = runCatching { api.fileContent("p10-visible.txt") }.getOrDefault("")
                serverDetail += " dirOnDisk=${written.isFile} size=${if (written.isFile) written.length() else -1}"
                runCatching { api.deleteSession(sid) }
            }
        } finally {
            // The project directory itself is left for the host script (it needs the
            // real path to `adb shell cat` the marker); the host script removes it.
        }

        val detail = "mode=${paths.mode} wsRoot=${root.absolutePath} fileManagerVisible=${paths.fileManagerVisible} " +
            "shared=$inShared underAndroidData=$underAndroidData underData=$inInternalData external=$inExternal " +
            "allFilesAccess=$granted grantHonoured=$grantHonoured legacyRoot=$internalRoot " +
            "serverWrote=$serverWrote readBack=${shellOutput.contains(marker)} " +
            "marker=$marker project=${project.name} $serverDetail"
        if (locationOk && grantHonoured && serverWrote) {
            gate("W4_WORKSPACE_VISIBLE", true, detail)
        } else {
            // Never a silent pass: a wrong location or an unwritable root is a FAIL
            // with the paths in the message, which is the whole point of the gate.
            gate("W4_WORKSPACE_VISIBLE", false, detail)
        }
    }
}
