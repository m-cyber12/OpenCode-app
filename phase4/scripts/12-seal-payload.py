#!/usr/bin/env python3
"""Build the SEALED payload assets for one ABI.

A sealed payload is the only shape in which a userland can ship through the
Play store: the store forbids downloading executable code, and Android 10+ (API
29) refuses to execute anything an app wrote into its own data directory. So the
payload is split in two halves, and this script is the thing that splits it:

  executables  ship inside the APK as native libraries (`lib<name>.so`, which
               the platform extracts into `nativeLibraryDir`) and are linked
               into the prefix at first run as symlinks. Recorded in
               `sealed-exec-<abi>.txt` as `<prefix path> <TAB> <library>`.
  data         everything that is only READ (config, share, /etc, a language's
               stdlib, package databases) goes into `userland-<abi>.zip` with
               `sealed-manifest-<abi>.txt` (sha256 + size per file), and is
               verified against that manifest while it is written.

The rule that makes the split safe is enforced here rather than remembered:
a file that would have to be EXECUTED is refused as data. That is checked with
the same two tests the on-device classifier uses (ELF magic, or a `#!` first
line), so a build that accidentally drops a binary into the data half fails here,
in CI, instead of working on an old device and dying on a new one.

Usage:
  12-seal-payload.py --self-test
  12-seal-payload.py --abi arm64-v8a --out DIR
                     [--exec-file FILE] [--exec-map PATH=LIB]...
                     [--data-dir DIR]

`--data-dir` is the prefix-shaped tree of read-only files. Symlinks inside it
are recorded (not followed) in `sealed-symlinks-<abi>.txt`, because a link is
data too and re-creating it is cheaper and truer than copying what it points at.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile

ELF_MAGIC = b"\x7fELF"
ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)


class SealError(Exception):
    """A payload that cannot be sealed as given."""


def normalize(path):
    """Prefix-relative and safe, or None. Mirrors RuntimeManifest.normalize."""
    if path is None:
        return None
    trimmed = path.strip().replace("\\", "/")
    while trimmed.startswith("./"):
        trimmed = trimmed[2:]
    if not trimmed or trimmed.startswith("/") or ".." in trimmed.split("/"):
        return None
    return trimmed


def classify(head):
    """ELF, script or data - the same rule as ExecutableProbe.classify."""
    if len(head) >= 4 and head[:4] == ELF_MAGIC:
        return "ELF"
    if len(head) >= 2 and head[:2] == b"#!":
        return "SCRIPT"
    return "DATA"


def kind_of(path):
    with open(path, "rb") as handle:
        return classify(handle.read(4))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_exec_records(pairs, path=None):
    """`path <TAB> library` records from --exec-map entries and/or a file."""
    lines = []
    for pair in pairs or []:
        if "=" not in pair:
            raise SealError("--exec-map expects <prefix path>=<library>, got: %s" % pair)
        prefix, library = pair.split("=", 1)
        lines.append("%s\t%s" % (prefix.strip(), library.strip()))
    if path:
        with open(path, "r", encoding="utf-8") as handle:
            lines.extend(handle.read().splitlines())
    records = []
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            raise SealError("bad exec record (want '<prefix path> <library>'): %r" % line)
        prefix = normalize(parts[0])
        library = normalize(parts[1])
        if prefix is None or library is None:
            raise SealError("unsafe exec record (absolute or '..'): %r" % line)
        records.append((prefix, library))
    # Sorted: the same inputs in any order must produce the same asset bytes.
    return sorted(records)


def collect_data(root, exec_paths):
    """Walk `root`; return (files, links). Refuse anything that must execute."""
    files, links = [], []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(dirnames + filenames):
            full = os.path.join(dirpath, name)
            relative = os.path.relpath(full, root).replace(os.sep, "/")
            if os.path.islink(full):
                target = os.readlink(full)
                if target.startswith("/") or ".." in target.split("/"):
                    raise SealError("symlink escapes the payload: %s -> %s" % (relative, target))
                links.append((relative, target))
                continue
            if not os.path.isfile(full):
                continue
            kind = kind_of(full)
            if kind in ("ELF", "SCRIPT"):
                if relative in exec_paths:
                    # Mapped to a library already: it travels in the APK, not
                    # in the data half.
                    continue
                raise SealError(
                    "%s is %s and would have to be executed, so it cannot ship "
                    "as data: give it an --exec-map entry and build it as a "
                    "lib*.so" % (relative, kind)
                )
            files.append((relative, full))
    files.sort()
    links.sort()
    return files, links


def write_zip(path, files, root):
    """Deterministic zip: same tree in, byte-identical archive out."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for relative, full in files:
            info = zipfile.ZipInfo(relative, date_time=ZIP_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as handle:
                archive.writestr(info, handle.read())


def build(abi, out, exec_pairs=None, exec_file=None, data_dir=None):
    os.makedirs(out, exist_ok=True)
    records = parse_exec_records(exec_pairs, exec_file)
    exec_paths = {prefix for prefix, _ in records}
    report = {
        "abi": abi,
        "executables": len(records),
        "dataFiles": 0,
        "dataBytes": 0,
        "dataLinks": 0,
        "assets": [],
    }

    exec_asset = os.path.join(out, "sealed-exec-%s.txt" % abi)
    with open(exec_asset, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# sealed executables: every one is a symlink into the APK's\n")
        handle.write("# native library directory. Generated by 12-seal-payload.py.\n")
        for prefix, library in records:
            handle.write("%s\t%s\n" % (prefix, library))
    report["assets"].append(os.path.basename(exec_asset))

    if data_dir:
        files, links = collect_data(data_dir, exec_paths)
        zip_path = os.path.join(out, "userland-%s.zip" % abi)
        write_zip(zip_path, files, data_dir)
        manifest_path = os.path.join(out, "sealed-manifest-%s.txt" % abi)
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("# sha256<TAB>size<TAB>path - verified while written\n")
            for relative, full in files:
                handle.write("%s\t%d\t%s\n" % (sha256_of(full), os.path.getsize(full), relative))
        report["dataFiles"] = len(files)
        report["dataBytes"] = sum(os.path.getsize(full) for _, full in files)
        report["assets"].extend([os.path.basename(zip_path), os.path.basename(manifest_path)])
        if links:
            link_path = os.path.join(out, "sealed-symlinks-%s.txt" % abi)
            with open(link_path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write("# path<TAB>target - both prefix-relative\n")
                for relative, target in links:
                    handle.write("%s\t%s\n" % (relative, target))
            report["dataLinks"] = len(links)
            report["assets"].append(os.path.basename(link_path))

    with open(os.path.join(out, "sealed-report.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return report


def self_test():
    """Every promise this script makes, checked on a real temporary tree."""
    tmp = tempfile.mkdtemp(prefix="seal-selftest-")
    try:
        root = os.path.join(tmp, "prefix")
        os.makedirs(os.path.join(root, "etc"))
        os.makedirs(os.path.join(root, "lib"))
        os.makedirs(os.path.join(root, "bin"))
        with open(os.path.join(root, "etc/motd"), "wb") as handle:
            handle.write(b"welcome\n")
        with open(os.path.join(root, "lib/libssl.so.3"), "wb") as handle:
            handle.write(b"\x7fELF fake\n")

        # 1. the classifier agrees with the on-device one
        assert classify(b"\x7fELF\x02\x01") == "ELF"
        assert classify(b"#!/bin/sh") == "SCRIPT"
        assert classify(b"just text") == "DATA"
        assert classify(b"#") == "DATA"

        # 2. an executable in the data half is refused, and says why
        try:
            collect_data(root, set())
            raise AssertionError("an ELF in the data half was accepted")
        except SealError as refused:
            assert "lib/libssl.so.3" in str(refused), str(refused)
            assert "ELF" in str(refused), str(refused)

        # 2b. the same refusal for a script: it is never execve'd in a sealed
        #     prefix, but shipping it as data would mean it silently became one
        with open(os.path.join(root, "bin/deploy"), "wb") as handle:
            handle.write(b"#!/bin/sh\necho hi\n")
        try:
            collect_data(root, set())
            raise AssertionError("a script in the data half was accepted")
        except SealError as refused:
            assert "bin/deploy" in str(refused), str(refused)
            assert "SCRIPT" in str(refused), str(refused)

        # 3. mapped into the exec half, they are excluded from the data half
        files, links = collect_data(
            root, {"lib/libssl.so.3": "liboc_ssl.so", "bin/deploy": "liboc_sh.so"}
        )
        assert [name for name, _ in files] == ["etc/motd"], files

        # 4. unsafe exec records are refused
        for bad in ("../escape=lib.so", "/etc/passwd=lib.so"):
            try:
                parse_exec_records([bad])
                raise AssertionError("accepted unsafe record: %s" % bad)
            except SealError:
                pass

        # 5. the built assets verify against the tree, and are deterministic
        out_a = os.path.join(tmp, "out-a")
        out_b = os.path.join(tmp, "out-b")
        exec_pairs = [
            "bin/ocprobe=libocexecprobe.so",
            "lib/libssl.so.3=liboc_ssl.so",
            "bin/deploy=liboc_sh.so",
        ]
        report = build("arm64-v8a", out_a, exec_pairs=exec_pairs, data_dir=root)
        build("arm64-v8a", out_b, exec_pairs=exec_pairs, data_dir=root)
        assert report["executables"] == 3, report
        assert report["dataFiles"] == 1 and report["dataBytes"] == 8, report
        for name in ("sealed-exec-arm64-v8a.txt", "userland-arm64-v8a.zip",
                     "sealed-manifest-arm64-v8a.txt"):
            with open(os.path.join(out_a, name), "rb") as first:
                with open(os.path.join(out_b, name), "rb") as second:
                    assert first.read() == second.read(), "not deterministic: %s" % name

        with open(os.path.join(out_a, "sealed-manifest-arm64-v8a.txt"), encoding="utf-8") as handle:
            line = [l for l in handle.read().splitlines() if not l.startswith("#")][0]
        digest, size, name = line.split("\t")
        assert name == "etc/motd" and size == "8", line
        assert digest == sha256_of(os.path.join(root, "etc/motd")), line

        with open(os.path.join(out_a, "sealed-exec-arm64-v8a.txt"), encoding="utf-8") as handle:
            body = [l for l in handle.read().splitlines() if not l.startswith("#")]
        assert body == [
            "bin/deploy\tliboc_sh.so",
            "bin/ocprobe\tlibocexecprobe.so",
            "lib/libssl.so.3\tliboc_ssl.so",
        ], body

        print("SEAL SELFTEST PASS: 6 checks (classifier, executable-as-data refused, "
              "exec mapping excludes it, unsafe records refused, deterministic + verifiable assets)")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true", help="check this script and exit")
    parser.add_argument("--abi", help="ABI name, e.g. arm64-v8a")
    parser.add_argument("--out", help="directory to write the assets into")
    parser.add_argument("--exec-file", help="file of '<prefix path> <library>' records")
    parser.add_argument("--exec-map", action="append", default=[],
                        metavar="PATH=LIB",
                        help="one executable, repeatable: bin/git=libgit.so")
    parser.add_argument("--data-dir", help="prefix-shaped tree of read-only files")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.abi or not args.out:
        parser.error("--abi and --out are required unless --self-test is given")
    report = build(args.abi, args.out, args.exec_map, args.exec_file, args.data_dir)
    print("sealed %s: %d executables, %d data files (%d bytes), %d data links"
          % (report["abi"], report["executables"], report["dataFiles"],
             report["dataBytes"], report["dataLinks"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
