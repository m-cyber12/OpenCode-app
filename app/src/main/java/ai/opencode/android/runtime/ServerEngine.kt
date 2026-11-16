package ai.opencode.android.runtime

/**
 * The OpenCode server the runtime starts: the v2 server on Node 26
 * (`libnode26.so` + `node26-v2.zip`).
 *
 * The v1 Bun server was removed at the switch-over (owner decision 2026-11-05 and
 * 2026-11-16): there is one engine, so there is nothing to select.
 */
enum class ServerEngine(
    /** Health route on loopback (Basic auth). */
    val healthPath: String,
    /** Text the health body must contain to count as healthy. */
    val healthMarker: String,
) {
    V2(healthPath = "/api/info", healthMarker = "\"version\""),
    ;

    companion object {
        /** Asset name of the v2 bundle zip (opencode.mjs, assets/, launcher-v2.mjs). */
        const val V2_ASSET = "node26-v2.zip"

        /** OpenCode version the v2 bundle is built from (spike/opencode-v2 sets OPENCODE_VERSION). */
        const val V2_VERSION = "2.2.5"
    }
}
