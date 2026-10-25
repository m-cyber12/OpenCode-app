/*
 * Native npm/npx entry point for the sealed Android prefix.
 *
 * App data is not executable on Android 10+, so npm's JavaScript launchers
 * cannot be kernel-executed through their #! line. bin/npm and bin/npx point
 * at this PIE in nativeLibraryDir instead. It turns the invocation into:
 *
 *   <nativeLibraryDir>/libnode.so
 *     <filesDir>/usr/lib/node_modules/npm/bin/{npm,npx}-cli.js ...
 *
 * The JavaScript remains ordinary verified payload data; both executables are
 * APK native libraries. OPENCODE_FILES_DIR is set by RuntimeEnv for every
 * child. /proc/self/exe locates the sibling Node executable without baking an
 * application id (debug and release ids differ) into this binary.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *base_name(const char *path) {
    const char *slash = strrchr(path ? path : "", '/');
    return slash ? slash + 1 : path;
}

int main(int argc, char **argv) {
    const char *files = getenv("OPENCODE_FILES_DIR");
    if (files == NULL || files[0] != '/') {
        fputs("npm: OPENCODE_FILES_DIR is missing or not absolute\n", stderr);
        return 126;
    }

    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0 || n >= (ssize_t)sizeof(self) - 1) {
        fprintf(stderr, "npm: cannot resolve /proc/self/exe: %s\n", strerror(errno));
        return 126;
    }
    self[n] = '\0';
    char *slash = strrchr(self, '/');
    if (slash == NULL) {
        fputs("npm: native executable has no parent directory\n", stderr);
        return 126;
    }
    slash[1] = '\0';

    char node[PATH_MAX];
    char cli[PATH_MAX];
    const char *tool = strcmp(base_name(argv[0]), "npx") == 0 ? "npx" : "npm";
    if (snprintf(node, sizeof(node), "%slibnode.so", self) >= (int)sizeof(node) ||
        snprintf(cli, sizeof(cli), "%s/usr/lib/node_modules/npm/bin/%s-cli.js", files, tool) >= (int)sizeof(cli)) {
        fputs("npm: sealed path is too long\n", stderr);
        return 126;
    }

    char **next = calloc((size_t)argc + 2, sizeof(char *));
    if (next == NULL) {
        fputs("npm: out of memory constructing argv\n", stderr);
        return 126;
    }
    next[0] = node;
    next[1] = cli;
    for (int i = 1; i < argc; ++i) next[i + 1] = argv[i];
    next[argc + 1] = NULL;

    execv(node, next);
    fprintf(stderr, "npm: cannot start sealed node: %s\n", strerror(errno));
    free(next);
    return 126;
}
