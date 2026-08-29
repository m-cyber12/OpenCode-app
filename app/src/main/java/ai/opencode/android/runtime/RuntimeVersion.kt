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

    /** Static git built from source (NO_PERL=YesPlease musl/NDK static). */
    const val GIT_VERSION = "v2.48.1"

    /** ripgrep (x86_64 musl-static from the official release; arm64 NDK-built). */
    const val RIPGREP_VERSION = "15.1.0"

    /**
     * Bumped whenever the payload layout/manifest changes. Stored in the
     * extraction marker; a mismatch forces re-extraction.
     */
    const val PAYLOAD_VERSION = 4
}
