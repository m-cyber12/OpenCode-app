/*
 * exec-shim.c — the actual process entrypoint for the embedded Bun server.
 *
 * Why this exists: Android installs a seccomp filter in Zygote that denies
 * newer syscalls with SECCOMP_RET_TRAP -> a FATAL SIGSYS (not -ENOSYS). The
 * official @oven/bun-linux-*-android binary calls some of these during its
 * OWN native startup, before any JavaScript can run — observed:
 *
 *   Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP), syscall 441 (epoll_pwait2)
 *     then, with only a JS-side handler installed (too late):
 *   Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP), syscall 21  (access)
 *
 * so a handler loaded from launcher.js via bun:ffi is too late. This wrapper
 * is the executable the supervisor launches (shipped in nativeLibraryDir as
 * libexecshim.so). It installs the SIGSYS -> ENOSYS handler FIRST THING in
 * main(), before bun's code is mapped, then execve()s the real bun with the
 * same argv. Every trapped syscall then returns -ENOSYS and Bun's fallbacks
 * engage (epoll_pwait2 -> epoll_pwait, access -> the caller sees -1/ENOENT,
 * close_range -> fd loop, ...). On execve the handler resets, so we re-exec
 * INTO a wrapper process that stays alive; see note below.
 *
 * Note: execve replaces this image with bun, dropping the handler. Therefore
 * we do NOT exec bun directly — the signal handler is process state and would
 * vanish. Instead the wrapper installs the handler and then uses a small
 * launcher: it maps the bun ELF? No — simplest correct design: the wrapper
 * forks; the child installs the handler and uses fexecve? Signals still reset.
 *
 * Correct mechanism (what this file actually does): install the SIGSYS handler
 * in the current process, then exec bun via a NON-resetting path is impossible.
 * So instead we LD_PRELOAD? The handler must live in the address space of bun.
 * The clean answer: make this wrapper's handler survive by exec'ing bun with
 * seccomp STILL TRAPPING but the handler provided by a library loaded into bun.
 * That is exactly libseccompshim.so loaded via LD_PRELOAD, whose __attribute__((
 * constructor)) installs the handler before bun main() runs.
 *
 * So: libexecshim.so sets LD_PRELOAD=<nativeLibraryDir>/libseccompshim.so (if
 * not already set) and execve()s bun. libseccompshim.so provides a constructor
 * that installs the SIGSYS handler; because it is preloaded its constructor
 * runs during dynamic linking, before bun's main / native init.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <libgen.h>

int main(int argc, char **argv) {
    /* This wrapper is invoked as:  libexecshim.so <launcher.js> [args...]
       argv[0] is this binary; the Kotlin supervisor passes the bun target via
       OPENCODE_BUN_EXEC so we do not have to hard-code the nativeLibraryDir. */
    const char *bun = getenv("OPENCODE_BUN_EXEC");
    if (!bun || !*bun) {
        fprintf(stderr, "[exec-shim] OPENCODE_BUN_EXEC not set\n");
        return 64;
    }

    /* Prepend the seccomp handler library to LD_PRELOAD so it is loaded into
       bun's address space and its constructor installs the handler before
       bun's own initialization. */
    const char *shim = getenv("OPENCODE_SECCOMP_SHIM");
    if (shim && *shim) {
        const char *old = getenv("LD_PRELOAD");
        char buf[4096];
        if (old && *old)
            snprintf(buf, sizeof(buf), "%s:%s", shim, old);
        else
            snprintf(buf, sizeof(buf), "%s", shim);
        setenv("LD_PRELOAD", buf, 1);
    }

    /* argv for bun: [bun_path, <original args after this wrapper>]. */
    char **newargv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (!newargv) return 70;
    newargv[0] = (char *)bun;
    for (int i = 1; i < argc; i++) newargv[i] = argv[i];
    newargv[argc] = NULL;

    execv(bun, newargv);
    /* On success never returns. */
    fprintf(stderr, "[exec-shim] execv(%s) failed: ", bun);
    perror("");
    return 127;
}
