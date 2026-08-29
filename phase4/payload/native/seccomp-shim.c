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

static void sigsys_handler(int signo, siginfo_t *info, void *uctx) {
    (void)signo;
    (void)info;
    ucontext_t *uc = (ucontext_t *)uctx;
#if defined(__aarch64__)
    /* arm64: the saved PC points AT the trapping `svc #0`, so skip its 4 bytes
       and set x0 = -ENOSYS (the syscall return register). */
    uc->uc_mcontext.regs[0] = (unsigned long long)(-ENOSYS);
    uc->uc_mcontext.pc += 4;
#elif defined(__x86_64__)
    /* x86-64: on a seccomp TRAP the kernel has ALREADY advanced RIP past the
       2-byte `syscall` instruction (the frame points at the next insn), so we
       only set RAX = -ENOSYS and must NOT touch RIP. Verified on host with a
       BPF filter that traps syscall 441 (epoll_pwait2): the call returns
       -1/errno=ENOSYS while allowed syscalls are unaffected. */
    uc->uc_mcontext.gregs[REG_RAX] = (greg_t)(-ENOSYS);
#else
#error "seccomp-shim: unsupported ABI (need arm64-v8a or x86_64)"
#endif
}

static int installed = 0;

/* Called once from JS via bun:ffi. Returns 0 on success (mirrors sigaction). */
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
