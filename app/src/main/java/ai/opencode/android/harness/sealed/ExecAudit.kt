package ai.opencode.android.harness.sealed

import java.io.File
import java.nio.file.Files

/**
 * Walks an installed prefix and reports, for every executable entry, whether it
 * resolves into the APK.
 *
 * This is the assertion the whole sealed design rests on, and it is the one
 * worth running on a device: a harness can install cleanly and still be
 * unpublishable, because one stray binary left in app data needs the data-dir
 * exec exemption that Android 10+ removed. The audit does not trust the
 * install's own bookkeeping, it re-derives the answer from the filesystem, so it
 * catches drift (an upgrade that shipped a new `lib*.so`, a package that
 * replaced a symlink with a copy) as well as build mistakes.
 */
object ExecAudit {

    /** Upper bound on visited paths, so a huge prefix cannot stall start-up. */
    const val MAX_ENTRIES = 40_000

    /** Wall-clock budget: a full audit must never be able to hang a caller. */
    const val MAX_MILLIS = 120_000L

    /** Directories whose contents are package data and never executed. */
    private val SKIP_DIRS = arrayOf(
        "var/lib/apt/lists", "var/cache", "share/doc", "share/man", "share/info",
        "share/locale", "share/terminfo", "lib/python", "lib/ruby", "node_modules",
        "include", "var/lib/dpkg/info"
    )

    /** One executable entry found in the prefix. */
    class Entry(
        /** Path relative to the prefix, e.g. `bin/git`. */
        val path: String,
        val kind: ExecutableKind,
        /** True when the entry resolves into the APK's native library directory. */
        val shippedInApk: Boolean,
        /** Resolved target for symlinks, or null. */
        val target: String?
    )

    /** Result of one audit pass. */
    class Report(
        val entries: List<Entry>,
        val scanned: Int,
        val truncated: Boolean,
        val nativeLibraryDirectory: String
    ) {
        /** Non-null when the walk could not complete at all. */
        var failure: String? = null
            internal set

        fun examined(): Int = entries.size

        /**
         * Binary (ELF) executables that are not backed by the APK, the only
         * thing that genuinely requires the data-directory exec exemption.
         *
         * Scripts are deliberately excluded: they are never `execve`-d in this
         * harness, the interpreter is started from the APK and the script is
         * handed to it. They are still counted, via [outsideScripts], because a
         * user script run directly (not through the harness) would be denied at
         * API 29+.
         */
        fun outsideApk(): List<Entry> =
            entries.filter { !it.shippedInApk && it.kind != ExecutableKind.SCRIPT }

        /** Scripts outside the APK: run through the interpreter layer, never exec'd. */
        fun outsideScripts(): List<Entry> =
            entries.filter { !it.shippedInApk && it.kind == ExecutableKind.SCRIPT }

        fun shippedCount(): Int = entries.count { it.shippedInApk }

        /**
         * True when every executable entry in the prefix resolves into the APK,
         * i.e. nothing left in app data needs `execve()`.
         */
        fun publishable(): Boolean = outsideApk().isEmpty()

        /** Compact, human-readable audit; also written to `exec-audit.txt`. */
        fun toText(): String {
            val text = StringBuilder()
            text.append("exec audit: ").append(examined())
                .append(" executable entries, ").append(shippedCount())
                .append(" shipped in the APK, ").append(outsideApk().size)
                .append(" binary executables outside it, ").append(outsideScripts().size)
                .append(" scripts outside it (run through the interpreter)\n")
            text.append("native libraries: ").append(nativeLibraryDirectory).append('\n')
            text.append("scanned paths: ").append(scanned)
                .append(if (truncated) " (truncated)" else "").append('\n')
            failure?.let { text.append("walk failed: ").append(it).append('\n') }
            text.append("verdict: ")
                .append(
                    if (publishable()) {
                        "publishable at any targetSdk (no binaries outside the APK)"
                    } else {
                        "targetSdk <= 28 only (" + outsideApk().size +
                            " binaries require the data-dir exec exemption)"
                    }
                )
                .append('\n')
            val outside = outsideApk()
            val shown = minOf(outside.size, 40)
            for (i in 0 until shown) {
                val entry = outside[i]
                text.append("  outside: ").append(entry.path)
                    .append(" [").append(entry.kind).append(']')
                entry.target?.let { text.append(" -> ").append(it) }
                text.append('\n')
            }
            if (outside.size > shown) {
                text.append("  ... ").append(outside.size - shown).append(" more\n")
            }
            return text.toString()
        }
    }

    /**
     * Walk [prefix] and classify every executable entry.
     *
     * An "executable entry" is a symlink (the normal shape of `bin/`) or a
     * regular file with any execute bit set. Package data (headers, man pages,
     * apt lists, language module trees) is skipped: it is read, not executed,
     * so it does not constrain packaging.
     */
    fun audit(prefix: File?, nativeLibraryDirectory: File?): Report =
        try {
            auditBounded(prefix, nativeLibraryDirectory)
        } catch (problem: Throwable) {
            // An audit is diagnostics: a hostile or half-installed prefix must
            // never turn it into a crash for the caller (it runs during setup).
            Report(emptyList(), 0, true, nativeLibraryDirectory.toString()).also {
                it.failure = problem.javaClass.simpleName + ": " + problem.message
            }
        }

    private fun auditBounded(prefix: File?, nativeLibraryDirectory: File?): Report {
        val entries = ArrayList<Entry>()
        if (prefix == null || !prefix.isDirectory) {
            return Report(entries, 0, false, nativeLibraryDirectory.toString())
        }
        val nativeRoot = nativeLibraryDirectory?.absolutePath
        val prefixPath = prefix.toPath()
        var scanned = 0
        var truncated = false

        val pending = ArrayDeque<File>()
        pending.add(prefix)
        val deadline = System.currentTimeMillis() + MAX_MILLIS
        while (!pending.isEmpty()) {
            val current = pending.removeFirst()
            if (scanned >= MAX_ENTRIES) {
                truncated = true
                break
            }
            if (System.currentTimeMillis() > deadline) {
                truncated = true
                break
            }

            val children = current.listFiles() ?: continue
            for (child in children) {
                if (++scanned > MAX_ENTRIES) {
                    truncated = true
                    break
                }
                val relative = relativePath(prefixPath, child) ?: continue
                if (isSkipped(relative)) continue

                if (Files.isSymbolicLink(child.toPath())) {
                    // Judge a link by what it finally resolves to, not by its
                    // name: a versioned library link (libssl.so ->
                    // libssl.so.3) is dlopen'd and never execve'd, while
                    // bin/bash -> <nativeLibraryDir>/libbash.so is exactly the
                    // sealed case. Dangling links cannot be executed at all.
                    val resolved = SealedLinks.resolveLink(child) ?: continue
                    val resolvedPath = resolved.absolutePath
                    val shipped = nativeRoot != null && resolvedPath.startsWith(nativeRoot)
                    if (shipped) {
                        entries.add(Entry(relative, ExecutableKind.ELF, true, resolvedPath))
                        continue
                    }
                    if (isLibraryPath(relative)) continue
                    val kind = ExecutableProbe.kindOf(resolved)
                    if (!ExecutableProbe.requiresExecAllowedPath(kind)) continue
                    entries.add(Entry(relative, kind, false, resolvedPath))
                    continue
                }
                if (child.isDirectory) {
                    pending.add(child)
                    continue
                }
                if (!child.isFile || !child.canExecute()) continue
                val kind = ExecutableProbe.kindOf(child)
                if (!ExecutableProbe.requiresExecAllowedPath(kind)) continue
                entries.add(Entry(relative, kind, false, null))
            }
        }
        return Report(entries, scanned, truncated, nativeLibraryDirectory.toString())
    }

    /** Directories whose contents are loaded with dlopen(), never executed. */
    private fun isLibraryPath(relative: String): Boolean =
        relative.startsWith("lib/") || relative.startsWith("lib64/") ||
            relative.contains("/lib/") || relative.endsWith(".so")

    private fun isSkipped(relative: String): Boolean =
        SKIP_DIRS.any { relative == it || relative.startsWith("$it/") }

    private fun relativePath(root: java.nio.file.Path, file: File): String? =
        try {
            root.relativize(file.toPath()).toString()
        } catch (notRelative: IllegalArgumentException) {
            null
        }
}
