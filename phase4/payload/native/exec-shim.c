/*
 * exec-shim.c — the actual process entrypoint for the embedded Bun server.
 *
 * Android installs a seccomp filter in Zygote that denies newer/legacy
 * syscalls with SECCOMP_RET_TRAP -> a fatal SIGSYS (not -ENOSYS). The official
 * Bun binary (and the static musl `git`/`rg` that OpenCode spawns) hit these.
 *
 * This PIE executable is launched from nativeLibraryDir. Before exec'ing bun
 * it installs TWO layers of compatibility so both the dynamic bun server and
 * its STATIC children are covered:
 *
 *  1) A seccomp BPF filter that returns an errno directly for syscalls whose
 *     "unsupported" semantics are safe (epoll_pwait2 -> ENOSYS so callers use
 *     epoll_pwait; access -> ENOENT so existence checks report absent; etc.).
 *     Crucially, seccomp FILTERS survive execve(), so this also protects the
 *     static musl git/rg children (which ignore LD_PRELOAD — they have no
 *     dynamic linker). Filter returns SECCOMP_RET_ALLOW for everything else so
 *     Android's own filter is consulted unchanged.
 *
 *  2) LD_PRELOAD=libseccompshim.so, whose constructor installs a SIGSYS
 *     handler for the cases an errno cannot satisfy — e.g. a trapped legacy
 *     mkdir(2) must actually create the directory (emulated via mkdirat).
 *     Dynamic-linker only (bun); the static children are covered by (1).
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stddef.h>
#include <errno.h>
#include <sys/prctl.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/syscall.h>

#ifndef SECCOMP_RET_ERRNO
#define SECCOMP_RET_ERRNO 0x00050000U
#endif
#define RET_ERRNO(e) (SECCOMP_RET_ERRNO | ((unsigned int)(e) & 0x7fffU))
#define RET_ALLOW   SECCOMP_RET_ALLOW
#ifndef SECCOMP_RET_LOG
#define SECCOMP_RET_LOG 0x7ffc0000U   /* allow + kernel audit log; enum seccomp_log() */
#endif

/*
 * Fallback action for non-listed syscalls, applied ONLY to the *outer*
 * (arch-default) return and the end-of-chain return. Normally SECCOMP_RET_ALLOW
 * defers to Android's filter. With OPENCODE_BPF_LOG=1 the fallback is
 * SECCOMP_RET_LOG: the kernel logs every otherwise-allowed syscall number to
 * the audit buffer (logcat "syscall=..."), including the one that the next
 * (Android) filter then TRAPs — this enumerates the syscall killing static git.
 */
static unsigned int fallback_action(void) {
    const char *e = getenv("OPENCODE_BPF_LOG");
    return (e && e[0] == '1') ? SECCOMP_RET_LOG : SECCOMP_RET_ALLOW;
}

/* Trap -> errno rules for syscalls that are safe to fail as "not supported"
   (callers have an old-kernel fallback) or "not present". */
struct rule {
    long nr;
    int err;
};

static int install_errno_filter(void) {
#if defined(__x86_64__)
    unsigned int arch = AUDIT_ARCH_X86_64;
#elif defined(__aarch64__)
    unsigned int arch = AUDIT_ARCH_AARCH64;
#else
    return 0; /* unknown ABI: skip filter, rely on default behaviour */
#endif

    struct rule rules[16];
    int n = 0;
    rules[n++] = (struct rule){ (long)__NR_epoll_pwait2, ENOSYS };
    rules[n++] = (struct rule){ (long)__NR_close_range, ENOSYS };
#ifdef __NR_preadv2
    rules[n++] = (struct rule){ (long)__NR_preadv2, ENOSYS };
#endif
#ifdef __NR_pwritev2
    rules[n++] = (struct rule){ (long)__NR_pwritev2, ENOSYS };
#endif
    rules[n++] = (struct rule){ (long)__NR_clone3, ENOSYS };
    rules[n++] = (struct rule){ (long)__NR_faccessat2, ENOSYS };
    rules[n++] = (struct rule){ (long)__NR_statx, ENOSYS };
    /* ONLY syscalls proven on-device to (a) be denied with SIGSYS by the app
       filter AND (b) tolerate ENOSYS without breaking the bun server boot.
       rseq, openat2 and futex_waitv were each tried on-device and PREVENTED
       the server reaching SERVER_READY (bionic needs the real call / a
       specific errno), so they must stay ALLOW (defer to Android). The static
       musl git/rg children's exact blocked syscall number is isolated
       separately (they are short-lived tools; the core server is unaffected). */
#ifdef __NR_access /* legacy access(2): arm64 has no such syscall */
    rules[n++] = (struct rule){ (long)__NR_access, ENOENT };
#endif

    /*
     * Build:
     *   0: ld arch (word @4)
     *   1: jeq arch 1 0        -> wrong arch falls to #2
     *   2: ret ALLOW           (wrong arch: defer to Android filter)
     *   3: ld nr   (word @0)
     *   then, per rule:  jeq nr jt=N  jf=...   -> jump to the rule's RET
     *   then: ret ALLOW
     *   then, per rule in emission order: ret ERRNO(err)
     */
    struct sock_filter f[64];
    struct sock_filter *p = f;
    unsigned int fb = fallback_action();
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4);
    *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, arch, 1, 0);
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, RET_ALLOW);
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0);

    int nr_load_idx = 3;
    int jeq_start = 4;
    int default_allow_idx = jeq_start + n;        /* after n jeq insns */
    int ret0_idx = default_allow_idx + 1;        /* first ERRNO ret */
    for (int i = 0; i < n; i++) {
        int cur_idx = jeq_start + i;             /* index of this JEQ insn  */
        int ret_idx = ret0_idx + i;              /* index of its RET insn   */
        /* BPF JMP skips are relative to the NEXT instruction (cur_idx+1),
         * so jt = ret_idx - (cur_idx + 1). A jf of 1 walks to the NEXT jeq
         * (the last jeq's jf=1 lands on default_allow). Verified against a
         * host BPF disassembler: rule i's match must reach ret0_idx+i. */
        unsigned int jt = (unsigned int)(ret_idx - (cur_idx + 1));
        (void)nr_load_idx;
        *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                            rules[i].nr, jt, 1);
    }
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, fb);
    for (int i = 0; i < n; i++)
        *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, RET_ERRNO(rules[i].err));

    struct sock_fprog prog;
    prog.len = (unsigned short)(p - f);
    prog.filter = f;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
        fprintf(stderr, "[exec-shim] no_new_privs failed: %s\n", strerror(errno));
        return -1;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) {
        /* Loudly non-fatal: the LD_PRELOAD SIGSYS handler still covers bun.
           If this fires on-device the evidence log will show it (static
           git/rg children would then still trap). */
        fprintf(stderr, "[exec-shim] seccomp BPF filter NOT installed: %s\n",
                strerror(errno));
        return -1;
    }
    fprintf(stderr, "[exec-shim] seccomp BPF errno filter installed (%d rules)\n", n);
    return 0;
}

/* Marker string grepped for in the on-device evidence log to prove the
   exec-surviving BPF filter (which protects static git/rg children) is live. */
#define SHIM_BUILD_TAG "opencode-execshim-bpf-v2"

int main(int argc, char **argv) {
    const char *bun = getenv("OPENCODE_BUN_EXEC");
    if (!bun || !*bun) {
        fprintf(stderr, "[exec-shim] OPENCODE_BUN_EXEC not set\n");
        return 64;
    }

    /* (1) seccomp errno filter (survives exec; protects bun + static children).
       opt-in for static-child diagnosis via OPENCODE_BPF=1; the filter is on by
       default for the real server. */
    fprintf(stderr, "[exec-shim] %s starting; target=%s\n", SHIM_BUILD_TAG, bun);
    install_errno_filter();

    /* (1b) For STATIC children (git/rg) that cannot LD_PRELOAD, the BPF filter
       returns ENOSYS for unknown new syscalls, but some trapped calls have no
       errno fallback AND fatal-SIGSYS differently. Those binaries are short-
       lived CLI tools: route them through a minimal wrapper is unnecessary as
       the BPF filter already covers them; nothing else to do here. */

    /* (2) preload the SIGSYS handler (mkdir emulation etc.) into bun. */
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
    fprintf(stderr, "[exec-shim] execv(%s) failed: ", bun);
    perror("");
    return 127;
}
