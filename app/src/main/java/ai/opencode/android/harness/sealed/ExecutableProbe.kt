package ai.opencode.android.harness.sealed

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Classifies a file by its first bytes: ELF magic, `#!` or neither.
 *
 * Pure Kotlin on purpose: this runs in JVM unit tests, in the bootstrap and in
 * the on-device audit, so it must not touch Android APIs. Only the first few
 * bytes are read, so it is cheap enough to run over a whole prefix.
 */
object ExecutableProbe {

    /** `\x7fELF`. */
    private val ELF_MAGIC = byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /** Classify [file]; never throws. */
    fun kindOf(file: File?): ExecutableKind {
        if (file == null || !file.isFile) return ExecutableKind.UNREADABLE
        val head = ByteArray(4)
        val read: Int = try {
            FileInputStream(file).use { readFully(it, head) }
        } catch (unreadable: IOException) {
            return ExecutableKind.UNREADABLE
        }
        return classify(head, read)
    }

    /** Pure classification of an already-read head buffer. */
    fun classify(head: ByteArray, length: Int): ExecutableKind {
        if (length < 2) return ExecutableKind.DATA
        if (length >= 4 &&
            head[0] == ELF_MAGIC[0] && head[1] == ELF_MAGIC[1] &&
            head[2] == ELF_MAGIC[2] && head[3] == ELF_MAGIC[3]
        ) {
            return ExecutableKind.ELF
        }
        if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) return ExecutableKind.SCRIPT
        return ExecutableKind.DATA
    }

    /**
     * True when the kind can only run from an exec-allowed location.
     *
     * [ExecutableKind.DATA] is readable from anywhere, so it never constrains
     * the packaging.
     */
    fun requiresExecAllowedPath(kind: ExecutableKind): Boolean =
        kind == ExecutableKind.ELF || kind == ExecutableKind.SCRIPT

    private fun readFully(input: FileInputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }
}
