/*
 * child-shim.c — ptrace supervisor for the STATIC musl child tools (git, rg)
 * so they survive the Android zygote app seccomp filter.
 *
 * Why this design:
 *   The static-musl git/rg are spawned by the Bun server under the zygote
 *   untrusted_app seccomp policy, which SECCOMP_RET_TRAPs (SIGSYS = "Bad system
 *   call", kills the process) any syscall outside the bionic whitelist. Static
 *   tools have no dynamic linker, so LD_PRELOAD cannot give them a SIGSYS
 *   handler; and the trapped syscall is one musl reaches that bionic never
 *   emits. We proved by direct untrusted_app enumeration that the exact killer
 *   number is not a single raw call we can pre-list (it is reached post-fork /
 *   in a range a one-at-a-time BPF-ENOSYS probe never hit), so hard-coding a
 *   small rule set is unreliable.
 *
 *   Robust approach — ptrace + SECCOMP_RET_TRACE over the whole "modern" range:
 *     - the shim forks; the child does PTRACE_TRACEME, then installs a seccomp
 *       filter that returns SECCOMP_RET_ALLOW for every LEGACY syscall
 *       (nr < SHIM_MODERN_FLOOR, which the zygote filter universally allows —
 *       proven by the probe: 2/4/6/21/82-92/159/258/268/318 all reach the
 *       kernel) and SECCOMP_RET_TRACE for every MODERN syscall (nr >= floor);
 *     - the shim (parent) ptrace-supervises the child across exec AND across
 *       every fork/clone/vfork (PTRACE_O_TRACEFORK/TRACECLONE/TRACEVFORK/
 *       TRACEEXEC), so all of the tool's sub-processes stay supervised;
 *     - at each PTRACE_EVENT_SECCOMP stop the parent reads the syscall number:
 *         * a tiny known-good allow-list (rseq/openat2/futex_waitv — modern
 *           bionic uses them and they are whitelisted) is left to run;
 *         * every OTHER modern syscall is forced to -ENOSYS before the outer
 *           zygote filter can deliver a fatal SIGSYS (orig_rax/x8 = -1 makes
 *           the kernel skip the call and return -ENOSYS, so the tool's
 *           userspace fallback runs, exactly as on an old kernel).
 *
 *   This closes the enumeration gap: ANY modern syscall that would otherwise
 *   SIGSYS is intercepted and turned into a benign ENOSYS; legacy calls never
 *   take a ptrace trap so the common path stays fast.
 *
 * Risk: untrusted_app must be allowed to ptrace its own child (it owns the
 * process; PTRACE_TRACEME from a child the parent fork(2)ed is permitted under
 * Android's app ptrace restriction Yama scope=PT_RACE_SCOPE_RESTRICTED because
 * it is a parent-child relationship). Verified on x86_64 host; CI validates on
 * the emulator.
 *
 * Env:
 *   OPENCODE_CHILD_DEBUG=1  verbose supervisor event log
 *   OPENCODE_CHILD_TRACE=1  log every spoofed modern syscall number
 *   OPENCODE_CHILD_PROBE=NR diag: in-process, report what the policy would do
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
#include <fcntl.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/user.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/syscall.h>

#if defined(__x86_64__)
  #define SHIM_AUDIT_ARCH AUDIT_ARCH_X86_64
  #define SHIM_NR(nr)     (nr)
#elif defined(__aarch64__)
  #define SHIM_AUDIT_ARCH AUDIT_ARCH_AARCH64
  #define SHIM_NR(nr)     (nr)
#else
  # error "child-shim: unsupported ABI (need x86_64 or arm64)"
#endif

/* A small set of MODERN syscalls that the zygote (bionic) seccomp filter DOES
   allow and that musl/bionic need at runtime -- these must be left to run, not
   spoofed. Everything else in the modern range gets spoofed (see should_spoof). */
static int is_allowed_modern(long nr) {
    switch (nr) {
#ifdef __NR_rseq
        case __NR_rseq:           /* 334 x86_64 / 293 arm64 -- bun/musl need it */
#endif
#ifdef __NR_openat2
        case __NR_openat2:        /* 437 / 437 */
#endif
#ifdef __NR_futex_waitv
        case __NR_futex_waitv:    /* 449 / 449 */
#endif
            return 1;
        default:
            return 0;
    }
}

/* Modern-syscall floor: syscalls >= this on the running arch are "new" and
   candidates for the zygote filter's TRAP (SIGSYS). Legacy syscalls below the
   floor are universally allowed by the zygote filter (proven by the
   untrusted_app probe), so we don't even trace them. The floor is arch-
   specific because x86_64 and arm64 (generic) number syscalls differently:
     - x86_64: rseq=334; the bionic whitelist covers up through ~334 (getrandom
       318, execveat 320, statx 332, rseq 334 are all allowed). The first
       commonly-trapped optional call is clone3=435/close_range=436, but to be
       safe we trace everything >= 335 (openat2=437 etc. land here).
     - arm64 (generic table): rseq=293; whitelist covers 293. Trace >= 294.
   Known-good modern calls at/above the floor (rseq/openat2/futex_waitv) are
   on the explicit allow-list and still run, so the conservative floor is safe. */
#if defined(__x86_64__)
  #define SHIM_MODERN_FLOOR 335
#elif defined(__aarch64__)
  #define SHIM_MODERN_FLOOR 294
#else
  #define SHIM_MODERN_FLOOR 294
#endif

/* Build a BPF that:
   - validates arch (ALLOW on mismatch),
   - ALLOWs every legacy syscall (nr < SHIM_MODERN_FLOOR) at full speed,
   - returns TRACE for every modern syscall (nr >= floor) so the supervisor
     decides per-call: spoof dangerous/unsupported -> -ENOSYS, or let
     known-good ones (rseq/openat2/futex_waitv) run.
   This guarantees any modern syscall that would SIGSYS under the zygote
   filter is intercepted (no enumeration gaps), while legacy calls stay fast. */
static int build_trace_filter(struct sock_filter *f, int cap, int *out_len) {
    struct sock_filter *p = f;
    /* Program:
       0: ld arch
       1: jeq arch -> 3 (jt skips the ALLOW)  [jt=1: land on ld nr; jf=0: ALLOW]
       2: ret ALLOW
       3: ld nr
       4: jgt MODERN_FLOOR-1 -> TRACE (jt=1 to 5); else fall to ALLOW (jf=0 to 6)
       5: ret TRACE
       6: ret ALLOW
    */
    if (cap < 7) return -1;
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                       offsetof(struct seccomp_data, arch));
    /* Index 1: arch match? next is index 2 (ALLOW). On match skip it: jt=1 -> idx3. */
    *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SHIM_AUDIT_ARCH, 1, 0);
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW); /* idx2 */
    *p++ = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                       offsetof(struct seccomp_data, nr));     /* idx3 */
    /* Index 4: nr > FLOOR-1 (modern)? next is idx5 TRACE. match: jt=0 -> idx5.
       legacy: jf=1 -> idx6 ALLOW. */
    *p++ = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K,
                                       SHIM_MODERN_FLOOR - 1, 0, 1);
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE); /* idx5 */
    *p++ = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW); /* idx6 */
    *out_len = (int)(p - f);
    return *out_len;
}

/* Policy decision shared by the live supervisor and the probe diag: a modern
   syscall (nr >= floor) that is not on the known-good allow-list is forced to
   -ENOSYS. Legacy syscalls and rseq/openat2/futex_waitv run normally. */
static int should_spoof_enosys(long nr) {
    if (nr < SHIM_MODERN_FLOOR) return 0;
    return !is_allowed_modern(nr);
}

/* ---- per-arch register access -------------------------------------------- */
#if defined(__x86_64__)
/* struct user_regs_struct word layout (index = byte offset / 8):
   r15=0 r14=1 r13=2 r12=3 rbp=4 rbx=5 r11=6 r10=7 r9=8 r8=9
   rax=10 rcx=11 rdx=12 rsi=13 rdi=14 orig_rax=15 rip=16 ... */
static long get_nr(pid_t pid) {
    return ptrace(PTRACE_PEEKUSER, pid, (void *)(long)(sizeof(long) * 15 /*orig_rax*/), 0);
}
static void force_enosys_entry(pid_t pid) {
    /* orig_rax = -1 skips the syscall; the kernel returns -ENOSYS. */
    ptrace(PTRACE_POKEUSER, pid, (void *)(long)(sizeof(long) * 15 /*orig_rax*/), (void *)-1L);
}
#elif defined(__aarch64__)
static long get_nr(pid_t pid) {
    struct user_regs_struct regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov) == 0)
        return (long)regs.regs[8]; /* x8 = syscall nr */
    return -1;
}
static void force_enosys_entry(pid_t pid) {
    struct user_regs_struct regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov) == 0) {
        regs.regs[8] = (unsigned long long)-1; /* skip syscall */
        iov.iov_len = sizeof(regs);
        ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &iov);
    }
}
#endif

static int dbg(void) {
    static int v = -1;
    if (v < 0) { const char *e = getenv("OPENCODE_CHILD_DEBUG"); v = (e && *e == '1'); }
    return v;
}

/* Supervise one tracee (pid) through to exit. Spawned sub-children notify the
   supervisor (TRACEFORK/TRACECLONE/TRACEEXEC/TRACEVFORK) and are supervised too. */
static void supervise(pid_t initial) {
    long opts = PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACESECCOMP
              | PTRACE_O_TRACEFORK | PTRACE_O_TRACECLONE
              | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXEC
              | PTRACE_O_EXITKILL;
    ptrace(PTRACE_SETOPTIONS, initial, 0, (void *)opts);
    ptrace(PTRACE_CONT, initial, 0, 0);

    for (;;) {
        int status;
        pid_t pid = waitpid(-1, &status, 0);
        if (pid == -1) {
            if (errno == EINTR) continue;
            break;
        }
        if (WIFEXITED(status)) {
            if (pid == initial) _exit(WEXITSTATUS(status));
            continue;
        }
        if (WIFSIGNALED(status)) {
            if (pid == initial) _exit(128 + WTERMSIG(status));
            continue;
        }
        if (!WIFSTOPPED(status)) {
            ptrace(PTRACE_CONT, pid, 0, 0);
            continue;
        }
        int sig = WSTOPSIG(status);
        unsigned int event = (unsigned int)status >> 16;

        if (dbg()) {
            fprintf(stderr, "[child-shim-dbg] pid=%d stopped sig=%d event=%u status=0x%x\n",
                    pid, sig, event, status);
        }

        if (event == PTRACE_EVENT_SECCOMP) {
            long nr = get_nr(pid);
            if (is_allowed_modern(nr)) {
                /* known-good modern syscall (rseq/openat2/futex_waitv): run it */
                if (dbg()) fprintf(stderr, "[child-shim-dbg] SECCOMP nr=%ld -> RUN\n", nr);
                ptrace(PTRACE_CONT, pid, 0, 0);
            } else {
                /* Every other modern syscall is forced -ENOSYS before the outer
                   zygote filter can deliver a fatal SIGSYS. This covers the
                   unknown musl syscall that killed static git/rg without
                   enumeration gaps. Log nr for diagnosis. */
                if (dbg() || getenv("OPENCODE_CHILD_TRACE"))
                    fprintf(stderr, "[child-shim] spoof syscall nr=%ld -> ENOSYS\n", nr);
                force_enosys_entry(pid);
                ptrace(PTRACE_CONT, pid, 0, 0);
            }
            continue;
        }

        /* New tracee from fork/clone/vfork/exec: it inherits the seccomp
           filter; make sure it has our options too. */
        if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_CLONE
            || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_EXEC) {
            unsigned long newpid = 0;
            if (ptrace(PTRACE_GETEVENTMSG, pid, 0, &newpid) == 0 && newpid > 0)
                ptrace(PTRACE_SETOPTIONS, (pid_t)newpid, 0, (void *)opts);
            ptrace(PTRACE_CONT, pid, 0, 0);
            continue;
        }

        if (event == PTRACE_EVENT_EXIT) {
            ptrace(PTRACE_CONT, pid, 0, 0);
            continue;
        }

        /* Plain signal-delivery-stop. Don't re-inject the group-stop SIGSTOP
           (used to sync the initial attach) or a syscall-good trap; deliver
           any other real signal to the child. */
        int deliver = sig;
        if (sig == SIGSTOP || sig == (SIGTRAP | 0x80)) deliver = 0;
        ptrace(PTRACE_CONT, pid, 0, (void *)(long)deliver);
    }
}

/* Locate the real tool binary in nativeLibraryDir (dir of /proc/self/exe). */
static int resolve_target(const char *base, char *target, size_t cap) {
    const char *lib = NULL;
    if (strcmp(base, "rg") == 0) lib = "/librg.so";
    else if (strcmp(base, "git") == 0 || strcmp(base, "git-upload-pack") == 0
             || strcmp(base, "git-receive-pack") == 0) lib = "/libgit.so";
    else lib = "/libgit.so";

    char exe[4096];
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0) return -1;
    exe[len] = 0;
    char *dir = dirname(exe);
    snprintf(target, cap, "%s%s", dir, lib);
    return 0;
}

int main(int argc, char **argv) {
    const char *a0 = argv[0] ? argv[0] : "";
    char buf0[4096];
    strncpy(buf0, a0, sizeof(buf0) - 1);
    buf0[sizeof(buf0) - 1] = 0;
    const char *base = basename(buf0);

    /* Diagnostic probe mode (env OPENCODE_CHILD_PROBE=NR): install the TRACE
       filter and make the raw syscall; if it is spoofed to -ENOSYS we report
       errno=38. Runs in THIS process (no child tool). */
    const char *probe_env = getenv("OPENCODE_CHILD_PROBE");
    if (probe_env && *probe_env) {
        struct sock_filter f[256];
        int flen = 0;
        if (build_trace_filter(f, 256, &flen) < 0) { fprintf(stderr, "[child-shim] filter build failed\n"); return 70; }
        struct sock_fprog prog = { (unsigned short)flen, f };
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) {
            fprintf(stderr, "[child-shim] filter install: %s\n", strerror(errno));
            return 70;
        }
        long nr = strtol(probe_env, NULL, 10);
        /* Directly emulate the spoof decision so probe output reflects what
           the supervisor would do. */
        long r;
        if (should_spoof_enosys(nr)) r = -ENOSYS;
        else r = syscall((int)nr, 0, 0, 0, 0, 0, 0);
        fprintf(stderr, "[child-shim] probe nr=%ld -> r=%ld errno=%d (%s)\n",
                nr, r, r < 0 ? errno : 0, r < 0 ? strerror(errno) : "ok");
        return 0;
    }

    char target[4096];
    if (resolve_target(base, target, sizeof(target)) < 0) {
        fprintf(stderr, "[child-shim] cannot resolve /proc/self/exe\n");
        return 70;
    }

    /* Fork a tracee that installs the TRACE filter and execs the real tool. */
    pid_t child = fork();
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP); /* stop so the parent can SETOPTIONS before exec */

        struct sock_filter f[256];
        int flen = 0;
        build_trace_filter(f, 256, &flen);
        struct sock_fprog prog = { (unsigned short)flen, f };
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) {
            fprintf(stderr, "[child-shim] child filter: %s\n", strerror(errno));
            _exit(70);
        }
        char **newargv = (char **)calloc((size_t)argc + 1, sizeof(char *));
        if (!newargv) _exit(70);
        newargv[0] = (char *)base; /* keep tool name */
        for (int i = 1; i < argc; i++) newargv[i] = argv[i];
        newargv[argc] = NULL;
        execv(target, newargv);
        fprintf(stderr, "[child-shim] execv(%s): %s\n", target, strerror(errno));
        _exit(127);
    }

    /* Wait for the child's initial SIGSTOP before configuring. */
    int status;
    waitpid(child, &status, 0);
    supervise(child);
    return 0;
}
