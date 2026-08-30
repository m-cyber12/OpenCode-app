/*
 * child-shim.c — seccomp wrapper for the STATIC musl child tools (git, rg).
 *
 * Why this exists:
 *   The OpenCode server (Bun, bionic dynamic) is launched through
 *   libexecshim.so, which installs a seccomp ERRNO filter + LD_PRELOADs the
 *   SIGSYS handler. That filter survives fork+exec, BUT the syscalls the
 *   static-musl children (libgit.so / librg.so) trip are ones bionic itself
 *   uses successfully (rseq, openat2, futex_waitv): mapping those to ENOSYS
 *   in the server's filter breaks the Bun boot, so they are left ALLOW there.
 *   The static tools, however, are built with no rseq/openat2 dependency on
 *   success (musl probes and falls back) and are killed by SIGSYS on the
 *   Android zygote seccomp policy when they use any new optional call.
 *
 *   bin/git and bin/rg are symlinks to THIS executable. It:
 *     1. identifies the tool from argv[0] (basename "git" / "rg"),
 *     2. installs an AGGRESSIVE SECCOMP_RET_ERRNO filter covering every new
 *        optional syscall the app filter may TRAP (each has an old-kernel
 *        fallback / is a probe for a static musl tool),
 *     3. execv()s the real libgit.so / librg.so that sits next to it in
 *        nativeLibraryDir (dirname of /proc/self/exe).
 *
 * The aggressive filter is safe here because static musl does NOT require any
 * of these calls to succeed (rseq registers best-effort; there is no musl
 * openat2 wrapper); the same calls are deliberately NOT mapped for the Bun
 * server (see exec-shim.c), where bionic needs them.
 *
 * Build (NDK clang):
 *   <ndk>/.../bin/x86_64-linux-android29-clang  -O2 -fPIE -pie -o libchildshim.so
 *   <ndk>/.../bin/aarch64-linux-android29-clang -O2 -fPIE -pie -o libchildshim.so
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <libgen.h>
#include <sys/prctl.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/syscall.h>

#ifndef SECCOMP_RET_ERRNO
#define SECCOMP_RET_ERRNO 0x00050000U
#endif
#define RET_ERRNO(e) (SECCOMP_RET_ERRNO | ((unsigned int)(e) & 0x7fffU))

struct rule { long nr; int err; };

/* Every "new / optional" syscall a static musl git/rg might probe, each mapped
   to -ENOSYS so the tool takes its old-kernel fallback (or simply skips the
   optional feature). access() -> ENOENT (path-existence probe). */
static int build_rules(struct rule *r) {
    int n = 0;
    r[n++] = (struct rule){ (long)__NR_epoll_pwait2, ENOSYS };
    r[n++] = (struct rule){ (long)__NR_close_range,  ENOSYS };
#ifdef __NR_preadv2
    r[n++] = (struct rule){ (long)__NR_preadv2, ENOSYS };
#endif
#ifdef __NR_pwritev2
    r[n++] = (struct rule){ (long)__NR_pwritev2, ENOSYS };
#endif
    r[n++] = (struct rule){ (long)__NR_clone3, ENOSYS };
    r[n++] = (struct rule){ (long)__NR_faccessat2, ENOSYS };
    r[n++] = (struct rule){ (long)__NR_statx, ENOSYS };
#ifdef __NR_rseq
    r[n++] = (struct rule){ (long)__NR_rseq, ENOSYS };   /* musl: best-effort */
#endif
#ifdef __NR_openat2
    r[n++] = (struct rule){ (long)__NR_openat2, ENOSYS }; /* musl uses openat */
#endif
#ifdef __NR_futex_waitv
    r[n++] = (struct rule){ (long)__NR_futex_waitv, ENOSYS }; /* -> futex */
#endif
#ifdef __NR_process_madvise
    r[n++] = (struct rule){ (long)__NR_process_madvise, ENOSYS };
#endif
#ifdef __NR_cachestat
    r[n++] = (struct rule){ (long)__NR_cachestat, ENOSYS };
#endif
#ifdef __NR_set_mempolicy_home_node
    r[n++] = (struct rule){ (long)__NR_set_mempolicy_home_node, ENOSYS };
#endif
#ifdef __NR_map_shadow_stack
    r[n++] = (struct rule){ (long)__NR_map_shadow_stack, ENOSYS };
#endif
#ifdef __NR_landlock_create_ruleset
    r[n++] = (struct rule){ (long)__NR_landlock_create_ruleset, ENOSYS };
#endif
#ifdef __NR_landlock_add_rule
    r[n++] = (struct rule){ (long)__NR_landlock_add_rule, ENOSYS };
#endif
#ifdef __NR_landlock_restrict_self
    r[n++] = (struct rule){ (long)__NR_landlock_restrict_self, ENOSYS };
#endif
#ifdef __NR_memfd_secret
    r[n++] = (struct rule){ (long)__NR_memfd_secret, ENOSYS };
#endif
#ifdef __NR_access
    r[n++] = (struct rule){ (long)__NR_access, ENOENT };
#endif
    return n;
}

static int install_filter(void) {
#if defined(__x86_64__)
    unsigned int arch = AUDIT_ARCH_X86_64;
#elif defined(__aarch64__)
    unsigned int arch = AUDIT_ARCH_AARCH64;
#else
    return 0;
#endif
    struct rule rules[32];
    int n = build_rules(rules);

    struct sock_filter f[128];
    struct sock_filter *p = f;
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4);
    *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, arch, 1, 0);
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0);

    /* Layout (matches exec-shim.c, host-verified jump offsets):
     *   [0..3] arch check + nr load
     *   [4 .. 4+n-1]  n JEQ: jt -> rule's RET_ERRNO; jf=1 walks to next JEQ,
     *                 and the last JEQ's jf=1 lands on the default ALLOW.
     *   [4+n]         default SECCOMP_RET_ALLOW (defer to Android filter).
     *   [4+n+1 ..]    n RET_ERRNO (rule i's target = default_allow+1+i).
     */
    int jeq_start = 4;
    int default_allow_idx = jeq_start + n;
    int ret0_idx = default_allow_idx + 1;
    for (int i = 0; i < n; i++) {
        int cur = jeq_start + i, ret_idx = ret0_idx + i;
        unsigned int jt = (unsigned int)(ret_idx - (cur + 1));
        *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                            rules[i].nr, jt, 1);
    }
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    for (int i = 0; i < n; i++)
        *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, RET_ERRNO(rules[i].err));

    struct sock_fprog prog;
    prog.len = (unsigned short)(p - f);
    prog.filter = f;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
        fprintf(stderr, "[child-shim] no_new_privs failed: %s\n", strerror(errno));
        return -1;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) {
        fprintf(stderr, "[child-shim] filter NOT installed: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    /* Tool from argv[0] basename (the bin/git|bin/rg symlink path). */
    const char *a0 = argv[0] ? argv[0] : "";
    char buf0[4096];
    strncpy(buf0, a0, sizeof(buf0) - 1);
    buf0[sizeof(buf0) - 1] = 0;
    const char *base = basename(buf0);

    const char *lib = NULL;
    if (strcmp(base, "rg") == 0)            lib = "/librg.so";
    else if (strcmp(base, "git") == 0 ||
             strcmp(base, "git-upload-pack") == 0 ||
             strcmp(base, "git-receive-pack") == 0) lib = "/libgit.so";
    else {
        /* Fall back to an explicit override, else assume git. */
        const char *ov = getenv("OPENCODE_CHILD_TARGET");
        if (ov && *ov) { execv(ov, argv); perror("[child-shim] exec override"); return 127; }
        lib = "/libgit.so";
    }

    /* nativeLibraryDir = dirname of the resolved /proc/self/exe. */
    char exe[4096], target[4096];
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0) { fprintf(stderr, "[child-shim] readlink exe failed\n"); return 70; }
    exe[len] = 0;
    char *dir = dirname(exe);
    snprintf(target, sizeof(target), "%s%s", dir, lib);

    /* Install the aggressive ENOSYS filter; non-fatal (defer to exec attempt). */
    install_filter();

    /* Keep argv[0] as the tool name so git/rg behave normally; execv() takes the
       real executable path separately. */
    char **newargv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (!newargv) return 70;
    newargv[0] = (char *)base;
    for (int i = 1; i < argc; i++) newargv[i] = argv[i];
    newargv[argc] = NULL;

    execv(target, newargv);
    fprintf(stderr, "[child-shim] execv(%s) failed: %s\n", target, strerror(errno));
    return 127;
}
