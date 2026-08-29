// Top-level build file. Plugin versions are declared once (Phase 4 toolchain,
// pinned per versions.lock): AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9,
// compileSdk 34. CI (phase4/scripts/) produces the runtime payload; a local
// build needs `bash phase4/scripts/10-build-payload.sh` first.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
