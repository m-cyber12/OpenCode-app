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
    /** OpenCode upstream pin: the v2 release tag v2.2.5 (anomalyco/opencode). */
    const val OPENCODE_COMMIT = "9bbb8ce18c80f099e0e448775f59d965ead40a21"
    const val OPENCODE_VERSION = "2.2.5"

    /** Git built from source with the Android NDK/Bionic toolchain. */
    const val GIT_VERSION = "v2.48.1"

    /** ripgrep built from source for each Android ABI with Cargo + NDK. */
    const val RIPGREP_VERSION = "15.1.0"

    /**
     * Bumped whenever the payload layout/manifest changes. Stored in the
     * extraction marker; a mismatch forces re-extraction.
     */
    const val PAYLOAD_VERSION = 10   // 10 = switch-over: v2 (Node 26) server, tool data only in the tar; 9 = stdin server password + config guard; 8 = noexec shell patch; 7 = Phase 9: bare OPENCODE_VERSION + pre-seeded @opencode-ai/plugin (plugin-seed/)

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
