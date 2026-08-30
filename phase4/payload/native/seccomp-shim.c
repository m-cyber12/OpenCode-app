/*
 * seccomp-shim.c — Android app-uid seccomp compatibility for Bun.
 *
 * Android's Zygote installs a seccomp filter for every app process. Newer
 * Linux syscalls that the prebuilt bionic filter does not know are denied
 * with SECCOMP_RET_TRAP, which the kernel delivers as a fatal SIGSYS instead
 * of returning -ENOSYS. Bun (Zig) expects those syscalls to return -ENOSYS on
 * an older kernel and then falls back, but on the Android app UID the trap
 * kills the process the instant the event loop waits:
 *
 *   Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP), syscall 441 ... libbun.so
 *   Cause: seccomp prevented call to disallowed x86_64 system call 441
 *
 * (x86_64 syscall 441 = epoll_pwait2; the same class includes close_range,
 * preadv2 and pwritev2. Short-lived bun CLI calls exit before the event loop
 * blocks, so they survive; the long-lived server crashes immediately.)
 *
 * This shim installs a SIGSYS handler. When the filter traps ANY syscall the
 * handler sets the return register to -ENOSYS and skips the trapping
 * instruction, so Bun's own fallback paths engage (epoll_pwait2 -> epoll_pwait,
 * close_range -> fd loop, preadv2 -> readv). This mirrors the Termux/Bun
 * community port at the Zig source level; doing it in a small NDK-built
 * library that launcher.js loads through bun:ffi keeps us on the official,
 * pinned @oven/bun-*-android binaries.
 *
 * Build (NDK clang, links bionic libc which is always present):
 *   <ndk>/.../bin/aarch64-linux-android29-clang -O2 -fPIC -shared \
 *       -o libseccompshim.so seccomp-shim.c
 *   <ndk>/.../bin/x86_64-linux-android29-clang  -O2 -fPIC -shared \
 *       -o libseccompshim.so seccomp-shim.c
 */

/* bionic defines REG_RAX/REG_RIP in <ucontext.h> unconditionally; glibc (used
   only for the host-side syntax check) needs _GNU_SOURCE. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <signal.h>
#include <ucontext.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

/*
 * A seccomp TRAP must be translated into the errno the call would have
 * returned on an old/unsupported kernel so the caller takes its normal
 * fallback. Most "missing new syscall" cases want -ENOSYS, but file-existence
 * probes (access/faccessat) want -ENOENT: Bun's startup and recursive mkdir
 * call access(F_OK) to decide whether a path exists; mapping that to ENOSYS
 * aborts its global-dir setup ("ENOSYS: mkdir <xdg>/data/opencode"), whereas
 * ENOENT just means "absent" so mkdir proceeds (and recursive mkdir already
 * tolerates EEXIST on the leaves the host pre-created).
 *
 *  - ENOSYS: syscalls with a real userspace fallback when unsupported.
 *  - ENOENT: path-existence probes that should report "path not present".
 *
 * Uses __NR_* from <sys/syscall.h> (per-ABI from the NDK), so the table is
 * correct on both x86_64 (CI emulator) and arm64-v8a (devices).
 */
static int map_errno(long nr) {
    switch (nr) {
        case __NR_epoll_pwait2: return ENOSYS;   /* -> epoll_pwait */
        case __NR_close_range: return ENOSYS;    /* -> /proc/self/fd loop */
        case __NR_preadv2:     return ENOSYS;   /* -> preadv */
        case __NR_pwritev2:    return ENOSYS;   /* -> pwritev */
        case __NR_clone3:      return ENOSYS;   /* -> clone */
        case __NR_statx:       return ENOSYS;   /* -> fstatat */
        case __NR_faccessat2:  return ENOSYS;   /* -> faccessat libc wrapper */
        /* Raw access() (legacy): on arm64 it can only reach here as a true trap
           so report the probed path absent; on x86_64 access is EMULATED with
           faccessat in the handler and never reaches this mapping. */
        case __NR_access:      return ENOENT;
        default:               return ENOSYS;
    }
}

static long trap_number(ucontext_t *uc) {
#if defined(__aarch64__)
    /* arm64 syscall nr lives in regs[8] (x8) on entry. */
    return (long)uc->uc_mcontext.regs[8];
#elif defined(__x86_64__)
    /* x86_64 syscall nr lives in RAX on entry. */
    return (long)uc->uc_mcontext.gregs[REG_RAX];
#else
    return -1;
#endif
}

/*
 * Emulate an x86-64 legacy (non-*at) syscall that Android's filter blocks, using
 * the allowed "*at" variant against AT_FDCWD. Returns a result (>=0 success, or a
 * negative -errno) to place in the syscall return register.
 *
 * This is why mapping mkdir -> ENOSYS was wrong: the caller needs the directory
 * actually created, and mkdirat (bionic's own mkdir implementation) is permitted.
 */
#if defined(__x86_64__)
static long emulate(int nr,
                    long a0, long a1, long a2, long a3, long a4) {
    switch (nr) {
        case 83: { /* mkdir(path, mode) -> mkdirat(AT_FDCWD, path, mode) */
            const char *p = (const char *)a0;
            long r = syscall(__NR_mkdirat, AT_FDCWD, p, a1);
            if (r == 0) return 0;
            int e = errno;
            /* If the directory already exists (the host pre-creates the
               OpenCode XDG subtree), treat mkdir as success — Bun's recursive
               mkdir ignores EEXIST anyway. Re-check with faccessat to be sure. */
            if (syscall(__NR_faccessat, AT_FDCWD, p, 0 /*F_OK*/, 0) == 0)
                return 0;
            static int logged_mkdir = 0;
            if (!logged_mkdir) {
                logged_mkdir = 1;
                dprintf(2, "[seccomp] mkdirat(%s) failed errno=%d\n",
                        p ? p : "(null)", e);
            }
            return -e;
        }
        case 21:  /* access(path, mode) -> faccessat(AT_FDCWD, path, mode, 0) */
            return syscall(__NR_faccessat, AT_FDCWD, (const char *)a0, a1, 0);
        default:
            return -ENOSYS;
    }
}
#endif

static void sigsys_handler(int signo, siginfo_t *info, void *uctx) {
    (void)signo;
    (void)info;
    ucontext_t *uc = (ucontext_t *)uctx;
    long nr = trap_number(uc);

    long ret;
#if defined(__x86_64__)
    /* Arg registers on trap entry: rdi=a0, rsi=a1, rdx=a2, r10=a3, r8=a4. */
    long a0 = (long)uc->uc_mcontext.gregs[REG_RDI];
    long a1 = (long)uc->uc_mcontext.gregs[REG_RSI];
    long a2 = (long)uc->uc_mcontext.gregs[REG_RDX];
    long a3 = (long)uc->uc_mcontext.gregs[REG_R10];
    long a4 = (long)uc->uc_mcontext.gregs[REG_R8];
    /* Legacy syscalls bionic implements via an *at wrapper emulate cleanly. */
    ret = emulate(nr, a0, a1, a2, a3, a4);
    if (ret == -ENOSYS && nr != __NR_access) ret = -(long)map_errno(nr);
#else
    /* arm64 has no mkdir(2)/access(2) legacy syscalls (only the *at forms,
       which the filter allows), so every trap is an ENOSYS-style new call. */
    ret = -(long)map_errno(nr);
#endif

    static int logged_unknown[64];
    if (ret == -ENOSYS && nr >= 0 && nr < 64 && !logged_unknown[nr]) {
        logged_unknown[nr] = 1;
        dprintf(2, "[seccomp] trapped syscall %ld -> ENOSYS (no *at emulation)\n", nr);
    }

#if defined(__aarch64__)
    /* arm64: saved PC points AT the trapping `svc #0`; skip its 4 bytes and put
       the result in x0 (the syscall return register). */
    uc->uc_mcontext.regs[0] = (unsigned long long)ret;
    uc->uc_mcontext.pc += 4;
#elif defined(__x86_64__)
    /* x86-64: the kernel already advanced RIP past the 2-byte `syscall`; place
       the result in RAX (negative errno on failure, >=0 on success). */
    uc->uc_mcontext.gregs[REG_RAX] = (greg_t)ret;
#else
#error "seccomp-shim: unsupported ABI (need arm64-v8a or x86_64)"
#endif
}

static int installed = 0;

/* Install the SIGSYS handler. Returns 0 on success (mirrors sigaction). */
__attribute__((visibility("default"))) int opencode_seccomp_init(void) {
    if (installed) return 0;
    struct sigaction sa;
    /* bionic guarantees sigaction_t/stack are zero-init safe with explicit set. */
    sa.sa_sigaction = sigsys_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaddset(&sa.sa_mask, SIGSYS); /* block nested traps while handling */
    int rc = sigaction(SIGSYS, &sa, 0);
    if (rc == 0) installed = 1;
    return rc;
}

/* When LD_PRELOADed into bun (by libexecshim.so) this constructor runs during
   dynamic linking — BEFORE bun's own initialization where it traps `access`
   (syscall 21) and epoll_pwait2 (441). This is the load point that actually
   fires; the bun:ffi path in launcher.js is a redundant backstop. */
__attribute__((constructor))
static void opencode_seccomp_auto_init(void) {
    opencode_seccomp_init();
}
