package ai.opencode.android.runtime

/**
 * Pinned component versions for the embedded runtime.
 *
 * These MUST match the payload produced by `phase4/scripts/10-build-payload.sh`
 * and the entries in the repo-root `versions.lock` (Core Rule 7: versions are
 * pinned reproducibly). The payload's [RuntimeManifest] carries its own
 * version string; [EXPECTED_PAYLOAD_VERSION] is the value this app build knows
 * how to run, so a stale/partial extraction from an older app build triggers a
 * clean re-extraction.
 */
object RuntimeVersion {
    /** OpenCode upstream pin (anomalyco/opencode, branch dev). */
    const val OPENCODE_COMMIT = "05ea5073be967c779d326929b2de6228dda4159d"
    const val OPENCODE_VERSION = "1.18.23"

    /** Bun-for-Android (bionic-linked official npm @oven/bun-*-android). */
    const val BUN_VERSION = "1.3.14"

    /** Git built from source with the Android NDK/Bionic toolchain. */
    const val GIT_VERSION = "v2.48.1"

    /** ripgrep built from source for each Android ABI with Cargo + NDK. */
    const val RIPGREP_VERSION = "15.1.0"

    /**
     * Bumped whenever the payload layout/manifest changes. Stored in the
     * extraction marker; a mismatch forces re-extraction.
     */
    const val PAYLOAD_VERSION = 5

    /**
     * The manifest is generated from the same lockfile as this app. A payload
     * with a different runtime pin is not safe to execute: re-extracting it
     * would only install the incompatible artifact again. Return a reason so
     * the host can fail clearly instead of accepting an unpinned runtime.
     */
    fun validateManifest(manifest: RuntimeManifest): String? {
        val mismatches = buildList {
            if (manifest.payloadVersion != PAYLOAD_VERSION) {
                add("payloadVersion=${manifest.payloadVersion} expected=$PAYLOAD_VERSION")
            }
            if (manifest.opencodeCommit != OPENCODE_COMMIT) {
                add("opencodeCommit=${manifest.opencodeCommit} expected=$OPENCODE_COMMIT")
            }
            if (manifest.opencodeVersion != OPENCODE_VERSION) {
                add("opencodeVersion=${manifest.opencodeVersion} expected=$OPENCODE_VERSION")
            }
            if (manifest.bunVersion != BUN_VERSION) {
                add("bunVersion=${manifest.bunVersion} expected=$BUN_VERSION")
            }
            if (manifest.gitVersion != GIT_VERSION) {
                add("gitVersion=${manifest.gitVersion} expected=$GIT_VERSION")
            }
            if (manifest.rgVersion != RIPGREP_VERSION) {
                add("rgVersion=${manifest.rgVersion} expected=$RIPGREP_VERSION")
            }
        }
        return mismatches.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
