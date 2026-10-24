/*
 * exec-probe.c — the smallest PIE executable that proves the exec model the
 * coding harness depends on.
 *
 * Android 10 (API 29) and later refuse to execute a file an app wrote into its
 * own data directory: writable-then-executable is a W^X violation. The one
 * place an app may still execute is the APK's native library directory, which
 * the platform extracts itself. Everything the harness runs — bash, python,
 * node, git — has to live on the legal side of that line, so the rule is
 * measured here rather than assumed.
 *
 * This file is packaged as libocexecprobe.so only so the platform extracts it
 * into nativeLibraryDir; it is an executable (PIE/DYN), not a library. It
 * writes one marker line and exits 0. Deliberately no stdio and no allocator:
 * the app's seccomp filter answers unfamiliar syscalls with SIGSYS instead of
 * ENOSYS, and a probe that trips one would report a broken exec model that is
 * actually a broken probe.
 */
#include <unistd.h>

int main(void) {
    static const char marker[] = "OCEXECPROBE ok\n";
    write(1, marker, sizeof(marker) - 1);
    return 0;
}
