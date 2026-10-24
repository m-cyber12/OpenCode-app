package ai.opencode.android.harness.sealed

/**
 * What a file in the prefix actually is, from the point of view of the
 * install-time execution model.
 *
 * The distinction matters because Android 10+ denies `execve()` of files that
 * live under the app's data directory for apps targeting API 29+, while still
 * allowing `execve()` of files in the app's native library directory. A
 * publishable build therefore has to know, file by file, whether it is
 * _code that must be executed_ (and must ship inside the APK) or _data that is
 * only read_ (and may be extracted at first run).
 */
enum class ExecutableKind {
    /** ELF object: the kernel executes it directly (needs an exec-allowed path). */
    ELF,

    /** Script with a `#!` line: the kernel execs the interpreter named there. */
    SCRIPT,

    /** Anything else: configuration, documentation, package data, archives. */
    DATA,

    /** Path could not be read (permissions, race); treated as unknown. */
    UNREADABLE
}
