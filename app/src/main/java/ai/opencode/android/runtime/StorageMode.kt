package ai.opencode.android.runtime

/**
 * What the live project directory is visible to.
 *
 * This enum exists so the app can say the truth about storage in one place
 * instead of three half-truths. Android's rules differ per version and per
 * directory, and the product decision behind the Phase 10 continuation v3 is
 * "files must live where a real file manager can see them" — which is a property
 * of the directory, not of the app.
 */
enum class StorageMode {
    /** A folder the user picked through SAF, resolved to a real path. Visible everywhere. */
    CHOSEN,

    /** `Documents/OpenCode` on shared storage. Visible to file managers, MTP and adb. */
    PUBLIC,

    /**
     * `Android/data/<applicationId>/files/workspaces`. Reachable by `adb`/MTP on any
     * version; a file manager can browse it on Android 10 but NOT on Android 11+
     * (the platform blocks apps from another app's directory).
     */
    APP_EXTERNAL,

    /** `/data/data/<applicationId>/files/workspaces`. Invisible to everything but the app. */
    INTERNAL,
}
