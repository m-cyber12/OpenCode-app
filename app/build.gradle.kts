// :app — the native Android shell that owns the embedded OpenCode runtime.
//
// Packaging (Core Rule: no user downloads — everything ships in the APK):
//   * Executables (bun, git, rg per ABI) are packaged as JNI libs
//     (lib<name>.so under jniLibs/<abi>/). Android extracts these into the
//     read-only, exec-allowed nativeLibraryDir — the only place an app can
//     exec from on API 29+ (W^X). extractNativeLibs=true so they are real
//     files (compressed download size), see AndroidManifest.
//   * The OpenCode server bundle, node_modules, launcher and the runtime
//     manifest ship as a compressed tar.gz under assets/ and are extracted
//     (with sha256 validation) into filesDir on first run.
//
// The payload under phase4/out/engine/ is built by
// phase4/scripts/10-build-payload.sh on a networked runner; it is gitignored.
// The preBuild task fails fast with an actionable message if it is missing.
import org.gradle.api.GradleException
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.opencode.android"
        minSdk = 29          // W^X: exec only from nativeLibraryDir; ABI gating enforces arm64/x64
        targetSdk = 34
        versionCode = 4      // phase 4
        versionName = "1.18.23-phase4"   // tracks the pinned OpenCode version (versions.lock)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")   // arm64 ships; x86_64 for CI/emulator
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No proguard stripping: the app shell is small and keeps stack
            // traces readable for runtime diagnostics.
        }
        debug {
            applicationIdSuffix = ".debug"   // debug coexists; run-as works on debuggable builds
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // needed for BuildConfig.DEBUG (debug control receiver)
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets {
        getByName("main") {
            // Build-owned copies of the runner-generated payload (populated by
            // the verifyAndStagePayload task before build). These are stable
            // directories mergeAssets/mergeJniLibs always see.
            assets.srcDir(layout.buildDirectory.dir("generated/engine/assets").get().asFile)
            jniLibs.srcDir(layout.buildDirectory.dir("generated/engine/jniLibs").get().asFile)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true   // = extractNativeLibs: libs uncompressed-stored then extracted
        }
    }

    testOptions {
        unitTests.all { it.testLogging { events("passed", "skipped", "failed") } }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM so manifest parsing/markers are testable locally.
    testImplementation("org.json:json:20240303")
}

// Copy the runner-generated payload into build-owned dirs that mergeAssets /
// mergeJniLibs consume, after verifying it is complete. This both fails fast
// with an actionable message if 10-build-payload.sh was never run, and makes
// the merge tasks depend on a real Copy (so the files are always staged).
fun engineRoot(): File = rootProject.file("phase4/out/engine")
fun engineAssetsOut(): File = layout.buildDirectory.dir("generated/engine/assets").get().asFile
fun engineJniOut(): File = layout.buildDirectory.dir("generated/engine/jniLibs").get().asFile

val verifyAndStagePayload = tasks.register("verifyAndStagePayload") {
    group = "opencode"
    description = "Verifies and stages phase4/out/engine into generated asset/jniLibs dirs."
    doLast {
        val root = engineRoot()
        val srcAssets = File(root, "assets")
        val srcJni = File(root, "jniLibs")
        val mf = File(srcAssets, "runtime-manifest.json")
        if (!mf.isFile) {
            throw GradleException(
                "Embedded runtime payload missing: ${mf.path} not found.\n" +
                    "Run:  bash phase4/scripts/10-build-payload.sh   (needs network; see phase4/README.md)\n" +
                    "It builds Bun-for-Android, static git, ripgrep and the pinned OpenCode bundle " +
                    "into phase4/out/engine/."
            )
        }
        val libs = listOf("libbun.so", "libgit.so", "librg.so", "libseccompshim.so", "libexecshim.so")
        val abis = listOf("arm64-v8a", "x86_64")
        var completeAbis = 0
        for (abi in abis) {
            val abiDir = File(srcJni, abi)
            val present = libs.map { File(abiDir, it) }
            val have = present.count { it.isFile }
            if (have == 0) {
                println("Runtime payload note: ABI $abi not built (skipped; pass it to 10-build-payload.sh).")
                continue
            }
            if (have != libs.size) {
                val missing = present.filterNot { it.isFile }
                throw GradleException("Runtime payload incomplete for ABI $abi: missing ${missing.map { it.name }}")
            }
            completeAbis++
        }
        if (completeAbis == 0) {
            throw GradleException("Runtime payload incomplete: no JNI exec libs found under $srcJni")
        }
        val assetsDst = engineAssetsOut()
        val jniDst = engineJniOut()
        assetsDst.deleteRecursively(); assetsDst.mkdirs()
        jniDst.deleteRecursively(); jniDst.mkdirs()
        srcAssets.copyRecursively(assetsDst, overwrite = true)
        srcJni.copyRecursively(jniDst, overwrite = true)
        println("Runtime payload OK: $completeAbis complete ABI(s); staged assets + jniLibs to $assetsDst / $jniDst")
    }
}

tasks.named("preBuild") {
    dependsOn(verifyAndStagePayload)
}
