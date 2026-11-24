/*
 * wget-compatible entry point for the sealed Android prefix.
 *
 * The payload already ships curl 8 with Mbed TLS and Android's live CA store.
 * Shipping a second TLS stack for GNU Wget would spend scarce Play base-module
 * bytes and duplicate the security boundary. This native PIE translates the
 * non-recursive download options coding agents use into that exact sealed curl
 * executable. Unsupported options fail loudly instead of silently changing
 * semantics.
 *
 * Both this launcher and libcurl.so execute from nativeLibraryDir. No script or
 * downloaded code is executed from writable app data.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void usage(FILE *out) {
    fputs(
        "OpenCode wget compatibility launcher (sealed curl/Mbed TLS backend)\n"
        "Usage: wget [-qS46c] [-O FILE] [-P DIR] [-T SEC] [-U AGENT] URL...\n"
        "Supported long options: --quiet, --continue, --output-document,\n"
        "  --directory-prefix, --timeout, --user-agent, --header, --spider,\n"
        "  --no-check-certificate, --ca-certificate, --compression=auto\n",
        out
    );
}

static int needs_value(const char *arg, const char *short_name, const char *long_name) {
    return strcmp(arg, short_name) == 0 || strcmp(arg, long_name) == 0;
}

static const char *long_value(const char *arg, const char *name) {
    size_t n = strlen(name);
    return strncmp(arg, name, n) == 0 && arg[n] == '=' ? arg + n + 1 : NULL;
}

static int push(char **next, size_t cap, size_t *used, char *value) {
    if (*used + 1 >= cap) return 0;
    next[(*used)++] = value;
    return 1;
}

int main(int argc, char **argv) {
    if (argc == 2 && (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0)) {
        usage(stdout);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts("wget (OpenCode sealed curl backend) 1");
        return 0;
    }

    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0 || n >= (ssize_t)sizeof(self) - 1) {
        fprintf(stderr, "wget: cannot resolve /proc/self/exe: %s\n", strerror(errno));
        return 126;
    }
    self[n] = '\0';
    char *slash = strrchr(self, '/');
    if (slash == NULL) {
        fputs("wget: native executable has no parent directory\n", stderr);
        return 126;
    }
    slash[1] = '\0';

    char curl[PATH_MAX];
    if (snprintf(curl, sizeof(curl), "%slibcurl.so", self) >= (int)sizeof(curl)) {
        fputs("wget: sealed curl path is too long\n", stderr);
        return 126;
    }

    size_t cap = (size_t)argc * 4 + 16;
    char **next = calloc(cap, sizeof(char *));
    if (next == NULL) {
        fputs("wget: out of memory constructing argv\n", stderr);
        return 126;
    }
    size_t used = 0;
    int has_output = 0;
    int saw_url = 0;
    for (int i = 1; i < argc; ++i) {
        const char *arg = argv[i];
        if (strcmp(arg, "-O") == 0 || strcmp(arg, "--output-document") == 0 ||
            strncmp(arg, "--output-document=", 18) == 0 ||
            (strncmp(arg, "-O", 2) == 0 && arg[2] != '\0') ||
            (strncmp(arg, "-qO", 3) == 0 && arg[3] != '\0')) {
            has_output = 1;
            break;
        }
    }
    push(next, cap, &used, curl);
    push(next, cap, &used, "--location");
    push(next, cap, &used, "--fail");
    if (!has_output) push(next, cap, &used, "--remote-name");

#define ADD(value) do { if (!push(next, cap, &used, (char *)(value))) goto too_many; } while (0)
#define VALUE_OR_FAIL() do { if (++i >= argc) goto missing_value; value = argv[i]; } while (0)

    for (int i = 1; i < argc; ++i) {
        const char *arg = argv[i];
        const char *value = NULL;
        if (strcmp(arg, "--") == 0) {
            while (++i < argc) { ADD(argv[i]); saw_url = 1; }
            break;
        } else if (strcmp(arg, "-q") == 0 || strcmp(arg, "--quiet") == 0) {
            ADD("--silent");
        } else if (strcmp(arg, "-nv") == 0 || strcmp(arg, "--no-verbose") == 0) {
            ADD("--silent"); ADD("--show-error");
        } else if (strcmp(arg, "-c") == 0 || strcmp(arg, "--continue") == 0) {
            ADD("--continue-at"); ADD("-");
        } else if (strcmp(arg, "-S") == 0 || strcmp(arg, "--server-response") == 0) {
            ADD("--dump-header"); ADD("-");
        } else if (strcmp(arg, "-4") == 0 || strcmp(arg, "--inet4-only") == 0) {
            ADD("--ipv4");
        } else if (strcmp(arg, "-6") == 0 || strcmp(arg, "--inet6-only") == 0) {
            ADD("--ipv6");
        } else if (strcmp(arg, "--spider") == 0) {
            ADD("--head");
        } else if (strcmp(arg, "--no-check-certificate") == 0) {
            ADD("--insecure");
        } else if (strcmp(arg, "--compression=auto") == 0) {
            ADD("--compressed");
        } else if (needs_value(arg, "-O", "--output-document")) {
            VALUE_OR_FAIL(); ADD("--output"); ADD(value); has_output = 1;
        } else if ((value = long_value(arg, "--output-document")) != NULL ||
                   (strncmp(arg, "-O", 2) == 0 && arg[2] != '\0' && (value = arg + 2))) {
            ADD("--output"); ADD(value); has_output = 1;
        } else if (needs_value(arg, "-P", "--directory-prefix")) {
            VALUE_OR_FAIL(); ADD("--output-dir"); ADD(value); ADD("--create-dirs");
        } else if ((value = long_value(arg, "--directory-prefix")) != NULL ||
                   (strncmp(arg, "-P", 2) == 0 && arg[2] != '\0' && (value = arg + 2))) {
            ADD("--output-dir"); ADD(value); ADD("--create-dirs");
        } else if (needs_value(arg, "-T", "--timeout")) {
            VALUE_OR_FAIL(); ADD("--max-time"); ADD(value);
        } else if ((value = long_value(arg, "--timeout")) != NULL ||
                   (strncmp(arg, "-T", 2) == 0 && arg[2] != '\0' && (value = arg + 2))) {
            ADD("--max-time"); ADD(value);
        } else if (needs_value(arg, "-U", "--user-agent")) {
            VALUE_OR_FAIL(); ADD("--user-agent"); ADD(value);
        } else if ((value = long_value(arg, "--user-agent")) != NULL ||
                   (strncmp(arg, "-U", 2) == 0 && arg[2] != '\0' && (value = arg + 2))) {
            ADD("--user-agent"); ADD(value);
        } else if (strcmp(arg, "--header") == 0) {
            VALUE_OR_FAIL(); ADD("--header"); ADD(value);
        } else if ((value = long_value(arg, "--header")) != NULL) {
            ADD("--header"); ADD(value);
        } else if (strcmp(arg, "--ca-certificate") == 0) {
            VALUE_OR_FAIL(); ADD("--cacert"); ADD(value);
        } else if ((value = long_value(arg, "--ca-certificate")) != NULL) {
            ADD("--cacert"); ADD(value);
        } else if (strncmp(arg, "-qO", 3) == 0 && arg[3] != '\0') {
            ADD("--silent"); ADD("--output"); ADD(arg + 3); has_output = 1;
        } else if (arg[0] == '-') {
            fprintf(stderr, "wget: unsupported option '%s' (use wget --help)\n", arg);
            free(next);
            return 2;
        } else {
            ADD(arg);
            saw_url = 1;
        }
    }

    if (!saw_url) {
        usage(stderr);
        free(next);
        return 2;
    }
    next[used] = NULL;
    execv(curl, next);
    fprintf(stderr, "wget: cannot start sealed curl: %s\n", strerror(errno));
    free(next);
    return 126;

missing_value:
    fprintf(stderr, "wget: option '%s' needs a value\n", argv[argc - 1]);
    free(next);
    return 2;
too_many:
    fputs("wget: too many arguments\n", stderr);
    free(next);
    return 2;
#undef ADD
#undef VALUE_OR_FAIL
}
