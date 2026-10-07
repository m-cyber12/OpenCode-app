package ai.opencode.android.addons

import org.json.JSONObject
import java.io.File

/**
 * v9.24 (owner): Add-ons - big artifacts the APP downloads once, so agents
 * never burn tokens (or 4G) fetching them inside a chat. The owner watched a
 * model download the wasm SWC compiler mid-session; the fix is to hold such
 * files in `<workspace root>/.addons/`, where every agent shell can reach
 * them with plain `tar`.
 *
 * Deliberate decisions:
 *  - the DOWNLOAD is done by the app itself (HttpURLConnection, progress,
 *    resume-by-restart), NOT by prompting a model - a download needs no
 *    intelligence and must not depend on a funded API key;
 *  - only the npm TARBALL is stored; extraction is the agent's one-liner
 *    (`tar -xzf`), because toybox tar is device-verified while a Java tar
 *    walker would be new untested surface;
 *  - W^X still holds: add-ons are DATA consumed by bun/next (wasm, JS),
 *    never native executables - those can only ship inside the APK.
 *
 * This object is the pure, JVM-tested part: URL building, manifest parsing,
 * file naming. The I/O lives in [AddonManager].
 */
object Addons {

    /** Directory under the workspace root; dot-named so project lists skip it. */
    const val DIR_NAME = ".addons"

    /**
     * The catalog. One entry today: the wasm SWC compiler that lets Next.js
     * dev servers run where native bindings cannot dlopen. ~100s of MB.
     */
    val CATALOG = listOf(
        AddonSpec(
            id = "next-swc-wasm",
            npmPackage = "@next/swc-wasm-nodejs",
        ),
    )

    data class AddonSpec(
        /** Stable id; also the tarball filename prefix. */
        val id: String,
        /** The npm package the tarball comes from. */
        val npmPackage: String,
    )

    data class Manifest(
        val version: String,
        val tarballUrl: String,
        /** Bytes after extraction, when the registry reports it (0 = unknown). */
        val unpackedSize: Long,
    )

    /**
     * Registry manifest URL for one version or dist-tag of a package.
     * Scoped names keep their `@` but the inner slash must be `%2F`
     * (`@next/swc-wasm-nodejs` -> `@next%2Fswc-wasm-nodejs`).
     */
    fun manifestUrl(registryBase: String, npmPackage: String, versionOrTag: String): String {
        val escaped = npmPackage.replace("/", "%2F")
        return registryBase.trimEnd('/') + "/" + escaped + "/" + versionOrTag
    }

    /** Parse the single-version manifest the registry returns for [manifestUrl]. */
    fun parseManifest(json: String): Manifest {
        val o = JSONObject(json)
        val dist = o.getJSONObject("dist")
        return Manifest(
            version = o.getString("version"),
            tarballUrl = dist.getString("tarball"),
            unpackedSize = dist.optLong("unpackedSize", 0L),
        )
    }

    /**
     * npm versions are dot/dash/alphanumeric; anything else (slashes, dots
     * pairs like `..`) must never reach a filename.
     */
    fun isSafeVersion(version: String): Boolean =
        version.isNotEmpty() &&
            version.length <= 64 &&
            !version.contains("..") &&
            version.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }

    /** `next-swc-wasm-15.5.3.tgz` - what the brief teaches agents to look for. */
    fun tarballName(spec: AddonSpec, version: String): String {
        require(isSafeVersion(version)) { "unsafe version: $version" }
        return "${spec.id}-$version.tgz"
    }

    /** The one place the directory shape is decided. */
    fun dir(workspaceRoot: File): File = File(workspaceRoot, DIR_NAME)

    /** Finished tarballs only - a crashed download leaves `.part`, never `.tgz`. */
    fun installed(workspaceRoot: File): List<File> =
        dir(workspaceRoot).listFiles { f -> f.isFile && f.name.endsWith(".tgz") }
            ?.sortedBy { it.name } ?: emptyList()
}
