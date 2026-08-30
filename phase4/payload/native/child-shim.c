/*
 * child-shim.c — compatibility launcher retained for older extracted layouts.
 *
 * The production PATH now points directly at libgit.so and librg.so. Both are
 * built from the real upstream sources for the Android ABI and link to Bionic,
 * so they do not need a static-musl seccomp or ptrace workaround. This small
 * PIE launcher remains packaged so an upgrade from an earlier Phase 4 build
 * cannot leave a dangling native artifact, and so diagnostics can still
 * resolve the old wrapper path safely. It deliberately does not attempt to
 * override Android's process seccomp policy: a child filter cannot replace a
 * parent filter's SIGSYS action, and ptrace is not available to untrusted_app.
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <libgen.h>

static int resolve_target(const char *base, char *target, size_t cap) {
    const char *lib = NULL;
    if (strcmp(base, "rg") == 0) {
        lib = "/librg.so";
    } else if (strcmp(base, "git") == 0 ||
               strcmp(base, "git-upload-pack") == 0 ||
               strcmp(base, "git-receive-pack") == 0) {
        lib = "/libgit.so";
    } else {
        const char *override = getenv("OPENCODE_CHILD_TARGET");
        if (override && *override) {
            if (snprintf(target, cap, "%s", override) >= (int)cap) return -1;
            return 0;
        }
        lib = "/libgit.so";
    }

    char exe[4096];
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0 || (size_t)len >= sizeof(exe) - 1) return -1;
    exe[len] = 0;
    char *dir = dirname(exe);
    if (snprintf(target, cap, "%s%s", dir, lib) >= (int)cap) return -1;
    return 0;
}

int main(int argc, char **argv) {
    char argv0[4096];
    strncpy(argv0, argv[0] ? argv[0] : "git", sizeof(argv0) - 1);
    argv0[sizeof(argv0) - 1] = 0;
    const char *base = basename(argv0);

    char target[4096];
    if (resolve_target(base, target, sizeof(target)) < 0) {
        fprintf(stderr, "[child-shim] cannot resolve native tool\n");
        return 70;
    }

    char **newargv = calloc((size_t)argc + 1, sizeof(char *));
    if (!newargv) return 70;
    newargv[0] = (char *)base;
    for (int i = 1; i < argc; ++i) newargv[i] = argv[i];
    newargv[argc] = NULL;
    execv(target, newargv);
    fprintf(stderr, "[child-shim] execv(%s): %s\n", target, strerror(errno));
    return 127;
}
