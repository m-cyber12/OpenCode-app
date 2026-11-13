package ai.opencode.android.runtime

/**
 * Which OpenCode server the runtime starts.
 *
 * V1: the Bun-based v1 server (libbun.so + launcher.js + runtime-payload).
 * V2: the OpenCode v2 server on Node 26 (libnode26.so + node26-v2.zip).
 *
 * Transitional selection (migration phase 1): V2 is used only when the APK carries
 * both the Node 26 binary and the v2 bundle asset. The release build does not carry
 * them yet, so release keeps V1 until the switch-over commit removes V1.
 */
enum class ServerEngine(
    /** Health route on loopback (Basic auth). */
    val healthPath: String,
    /** Text the health body must contain to count as healthy. */
    val healthMarker: String,
) {
    V1(healthPath = "/global/health", healthMarker = "\"healthy\""),
    V2(healthPath = "/api/info", healthMarker = "\"version\""),
    ;

    companion object {
        /** Asset name of the v2 bundle zip (opencode.mjs, assets/, launcher-v2.mjs). */
        const val V2_ASSET = "node26-v2.zip"

        /** OpenCode version the v2 bundle is built from (spike/opencode-v2 sets OPENCODE_VERSION). */
        const val V2_VERSION = "2.2.5"

        /** Pure selection rule, kept separate for JVM tests. */
        fun select(hasNode26Binary: Boolean, hasV2Asset: Boolean): ServerEngine =
            if (hasNode26Binary && hasV2Asset) V2 else V1
    }
}
