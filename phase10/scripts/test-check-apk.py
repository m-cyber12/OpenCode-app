#!/usr/bin/env python3
"""Phase 10 self-test for check-apk.py.

A checker nobody has ever run is not evidence (Core Rule 4), and there is no
Gradle/JDK in the authoring sandbox to produce a real APK from. So this test
ENCODES a binary AndroidManifest.xml from scratch, packs it into a zip shaped
like an APK, and asserts that check-apk.py reads back exactly what was encoded -
including a deliberately wrong expectation, to prove the checker can FAIL.

It also encodes the two things a store review cares about most: a debuggable
manifest must trip --expect-not-debuggable, and an artifact with an
apk-signing-block-like trailer must be reported as signed.

Usage: python3 phase10/scripts/test-check-apk.py        (exit 0 = the checker works)
"""
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check-apk.py")
sys.path.insert(0, HERE)
import importlib.util  # noqa: E402

spec = importlib.util.spec_from_file_location("check_apk", CHECK)
check_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_apk)

UTF8_FLAG = 1 << 8
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
NO_INDEX = 0xFFFFFFFF
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12
TYPE_REFERENCE = 0x01


def encode_utf8(s):
    b = s.encode("utf-8")
    if len(b) > 0x7F:
        return bytes([0x80 | (len(s) >> 8), len(s) & 0xFF]) + bytes([0x80 | (len(b) >> 8), len(b) & 0xFF]) + b + b"\x00"
    return bytes([len(s), len(b)]) + b + b"\x00"


def string_pool(strings):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        data += encode_utf8(s)
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    size = header_size + len(body)
    chunk = struct.pack("<HHIIIIII", RES_STRING_POOL_TYPE, header_size, size,
                        len(strings), 0, UTF8_FLAG,
                        header_size + 4 * len(strings), 0) + body
    return chunk


def start_element(name_idx, attrs):
    """Encode a ResXMLTree START_ELEMENT chunk.

    Layout (this is the part every hand-written AXML encoder gets wrong): the
    chunk header is ResChunk_header(8) + lineNumber(4) + comment(4) = 16 bytes,
    then ResXMLTree_attrExt { ns(4), name(4), attributeStart(2),
    attributeSize(2), attributeCount(2), idIndex(2), classIndex(2),
    styleIndex(2) } = 20 bytes, then the attributes at chunk+16+attributeStart.
    """
    # ResXMLTree_attribute: ns, name, rawValue (ResStringPool_ref; 0xFFFFFFFF =
    # "no raw string", which is the normal case for typed non-string values),
    # then Res_value {size, res0, dataType, data}.
    attr_blob = b"".join(
        struct.pack("<IIIHBBI",
                    ns if ns >= 0 else NO_INDEX,
                    n,
                    raw if raw >= 0 else NO_INDEX,
                    8, 0, t, d)
        for (ns, n, raw, (t, d)) in attrs
    )
    body = struct.pack("<II", NO_INDEX, name_idx)          # ns, name
    body += struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0)
    body += attr_blob
    size = 16 + len(body)
    return struct.pack("<HHII", RES_XML_START_ELEMENT_TYPE, 16, size, 1) + \
        struct.pack("<I", NO_INDEX) + body


def end_element(name_idx):
    # ResXMLTree_node: header(8) + lineNumber(4) + comment(4); then ns(4) + name(4).
    size = 8 + 4 + 4 + 4 + 4
    return struct.pack("<HHII", RES_XML_END_ELEMENT_TYPE, 16, size, 1) + \
        struct.pack("<I", NO_INDEX) + struct.pack("<II", NO_INDEX, name_idx)


def build_axml(package="io.github.mcyber12.opencode", version_code=8,
               version_name="1.18.23-phase10", debuggable=False, with_icon=True,
               label_mode="string"):
    """label_mode selects how android:label is ENCODED, which is the whole point
    of the run-#6 regression:
      "string"    TYPE_STRING -> "@string/app_name" (the test's original shape)
      "ref"       TYPE_REFERENCE -> a resource id int, exactly what real aapt2
                  emits for @string/app_name in a binary manifest
      "literal"   TYPE_STRING -> "OpenCode" (a translatable-nit the check rejects)
    """
    strings = []
    idx = {}

    def s(v):
        if v not in idx:
            idx[v] = len(strings)
            strings.append(v)
        return idx[v]

    # Reserve the strings first so indices are stable.
    for v in ["manifest", "package", "versionCode", "versionName",
              "http://schemas.android.com/apk/res/android", "uses-sdk",
              "minSdkVersion", "targetSdkVersion", "application",
              "allowBackup", "extractNativeLibs", "networkSecurityConfig",
              "debuggable", "label", "icon", "roundIcon", "uses-permission",
              "name", "activity", "service", "exported",
              ".MainActivity", ".runtime.RuntimeService",
              "android.permission.INTERNET", "@string/app_name", "OpenCode", "true", "false"]:
        s(v)

    F = NO_INDEX
    android_ns = s("http://schemas.android.com/apk/res/android")
    axml = start_element(
        s("manifest"),
        [(F, s("package"), s(package), (TYPE_STRING, s(package))),
         (F, s("versionCode"), -1, (TYPE_INT_DEC, version_code)),
         (F, s("versionName"), s(version_name), (TYPE_STRING, s(version_name)))]
        + ([(android_ns, s("versionName"), s(version_name), (TYPE_STRING, s(version_name)))] if False else []),
    )
    axml += start_element(
        s("uses-sdk"),
        [(android_ns, s("minSdkVersion"), -1, (TYPE_INT_DEC, 29)),
         (android_ns, s("targetSdkVersion"), -1, (TYPE_INT_DEC, 34))],
    )
    axml += end_element(s("uses-sdk"))
    app_attrs = [
        (android_ns, s("allowBackup"), -1, (TYPE_INT_BOOLEAN, 0)),
        (android_ns, s("extractNativeLibs"), -1, (TYPE_INT_BOOLEAN, 1)),
        (android_ns, s("networkSecurityConfig"), -1, (TYPE_REFERENCE, 0x7F123456)),
    ]
    if label_mode == "ref":
        # what real aapt2 writes for @string/app_name: a TYPE_REFERENCE whose
        # data is the resource id, with no raw string at all
        app_attrs.append((android_ns, s("label"), -1, (TYPE_REFERENCE, 0x7F0A0001)))
    elif label_mode == "literal":
        app_attrs.append((android_ns, s("label"), s("OpenCode"), (TYPE_STRING, s("OpenCode"))))
    else:
        app_attrs.append((android_ns, s("label"), s("@string/app_name"), (TYPE_STRING, s("@string/app_name"))))
    if with_icon:
        app_attrs.append((android_ns, s("icon"), -1, (TYPE_REFERENCE, 0x7F0F0000)))
        app_attrs.append((android_ns, s("roundIcon"), -1, (TYPE_REFERENCE, 0x7F0F0001)))
    if debuggable:
        app_attrs.append((android_ns, s("debuggable"), -1, (TYPE_INT_BOOLEAN, 1)))
    axml += start_element(s("application"), app_attrs)
    axml += start_element(
        s("uses-permission"),
        [(android_ns, s("name"), s("android.permission.INTERNET"),
          (TYPE_STRING, s("android.permission.INTERNET")))],
    )
    axml += end_element(s("uses-permission"))
    axml += start_element(
        s("activity"),
        [(android_ns, s("name"), s(".MainActivity"), (TYPE_STRING, s(".MainActivity"))),
         (android_ns, s("exported"), -1, (TYPE_INT_BOOLEAN, 1))],
    )
    axml += end_element(s("activity"))
    axml += start_element(
        s("service"),
        [(android_ns, s("name"), s(".runtime.RuntimeService"),
          (TYPE_STRING, s(".runtime.RuntimeService")))],
    )
    axml += end_element(s("service"))
    axml += end_element(s("application"))
    axml += end_element(s("manifest"))

    pool = string_pool(strings)
    ids = [0x01010003, 0x01010001, 0x01010000]   # name, label, theme-ish
    resmap = struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, 8, 8 + 4 * len(ids))
    resmap += b"".join(struct.pack("<I", i) for i in ids)
    body = resmap + axml
    # The top-level XML chunk's size covers the header, the string pool and the
    # element stream: get this wrong and every chunk after the pool is misaligned.
    total = 8 + len(pool) + len(body)
    header = struct.pack("<HHI", RES_XML_TYPE, 8, total)
    return header + pool + body


def make_apk(path, axml, signed=False, payload=True, abis=("arm64-v8a", "x86_64"),
              payload_name="assets/runtime-payload.tar.gz"):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("AndroidManifest.xml", axml)
        zf.writestr("resources.arsc", b"\x02\x00\x0c\x00" + b"\x00" * 64)
        zf.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 64)
        if payload:
            zf.writestr("assets/runtime-manifest.json", '{"payloadVersion": 7}\n')
            zf.writestr(payload_name, b"\x1f\x8b\x08\x00" + b"\x00" * 512)
        for abi in abis:
            for lib in ("libbun.so", "libgit.so", "librg.so", "libseccompshim.so",
                        "libexecshim.so", "libchildshim.so"):
                zf.writestr("lib/%s/%s" % (abi, lib), b"\x7fELF" + b"\x00" * 256)
        zf.writestr("res/mipmap-anydpi-v26/ic_launcher.xml", b"<adaptive-icon/>\n")
    if signed:
        # Append an APK Signing Block marker so the presence detector has
        # something to find (a real block has a precise structure; check-apk.py
        # reports presence, and `apksigner verify` does the cryptography).
        with open(path, "ab") as fh:
            fh.write(b"signed-marker" + check_apk.APK_SIG_BLOCK_MAGIC)


def run(args):
    p = subprocess.run([sys.executable, CHECK] + args, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def main():
    fails = []
    tmp = tempfile.mkdtemp(prefix="p10-check-apk-")
    apk = os.path.join(tmp, "app-release-unsigned.apk")
    make_apk(apk, build_axml())

    # 1. the parser reads back what was encoded
    with zipfile.ZipFile(apk) as zf:
        mf = check_apk.parse_manifest(zf.read("AndroidManifest.xml"))
    checks = [
        (mf["package"] == "io.github.mcyber12.opencode", "package read back: %r" % mf["package"]),
        (mf["versionCode"] == 8, "versionCode read back: %r" % mf["versionCode"]),
        (mf["versionName"] == "1.18.23-phase10", "versionName read back: %r" % mf["versionName"]),
        (mf["minSdk"] == 29 and mf["targetSdk"] == 34, "sdk levels: %r/%r" % (mf["minSdk"], mf["targetSdk"])),
        (mf["debuggable"] is False, "debuggable=false read back"),
        (mf["icon_declared"] and mf["round_icon_declared"], "icon + roundIcon read back"),
        (mf["label_is_resource"], "label is a @string reference"),
        (mf["permissions"] == ["android.permission.INTERNET"], "permissions: %r" % mf["permissions"]),
        (mf["allowBackup"] == "false" and mf["extractNativeLibs"] == "true", "application flags"),
        (mf["networkSecurityConfig"] is True, "networkSecurityConfig present"),
    ]
    # 1b. the label ENCODING regressions (CI run #6): a resource id written as an
    # int must be accepted; a literal string must still be flagged.
    ref_apk = os.path.join(tmp, "app-label-ref.apk")
    make_apk(ref_apk, build_axml(label_mode="ref"))
    with zipfile.ZipFile(ref_apk) as zf:
        mf_ref = check_apk.parse_manifest(zf.read("AndroidManifest.xml"))
    checks.append((mf_ref["label_is_resource"],
                   "int resource-id label (real aapt2 form) read as a resource"))
    checks.append((mf_ref.get("label_raw") == "0x7f0a0001",
                   "label_raw reports the reference: %r" % mf_ref.get("label_raw")))
    lit_apk = os.path.join(tmp, "app-label-literal.apk")
    make_apk(lit_apk, build_axml(label_mode="literal"))
    with zipfile.ZipFile(lit_apk) as zf:
        mf_lit = check_apk.parse_manifest(zf.read("AndroidManifest.xml"))
    checks.append((mf_lit["label_is_resource"] is False,
                   "literal string label rejected as not-a-resource"))
    checks.append((mf_lit.get("label_raw") == "OpenCode",
                   "label_raw reports the literal: %r" % mf_lit.get("label_raw")))
    for ok, msg in checks:
        if not ok:
            fails.append("parse: " + msg)

    # 2. full checker run with the right expectations -> PASS
    rc, out = run([apk, "--expect-package", "io.github.mcyber12.opencode",
                   "--expect-version-name", "1.18.23-phase10", "--expect-version-code", "8",
                   "--expect-not-debuggable", "--expect-unsigned", "--expect-icon",
                   "--expect-payload", "--expect-min-sdk", "29", "--expect-target-sdk", "34",
                   "--expect-native-abi", "arm64-v8a", "--expect-native-abi", "x86_64",
                   "--expect-permission", "android.permission.INTERNET",
                   "--expect-no-permission", "android.permission.READ_SMS"])
    if rc != 0 or "VERDICT PASS" not in out:
        fails.append("expected PASS on a matching artifact, rc=%d out=%s" % (rc, out[-400:]))

    # 3. a wrong expectation must FAIL (the checker is not a rubber stamp)
    rc, out = run([apk, "--expect-package", "ai.opencode.android"])
    if rc == 0 or "FINDING" not in out:
        fails.append("a wrong package expectation was accepted (rc=%d)" % rc)

    # 4. a debuggable manifest must trip --expect-not-debuggable
    dbg = os.path.join(tmp, "app-smoke.apk")
    make_apk(dbg, build_axml(debuggable=True))
    rc, out = run([dbg, "--expect-not-debuggable"])
    if rc == 0:
        fails.append("debuggable=true passed --expect-not-debuggable")

    # 5. a missing payload must trip --expect-payload
    nop = os.path.join(tmp, "app-nopayload.apk")
    make_apk(nop, build_axml(), payload=False)
    rc, out = run([nop, "--expect-payload"])
    if rc == 0 or "runtime-manifest.json missing" not in out:
        fails.append("a payload-less artifact passed --expect-payload")

    # 6. signature presence: unsigned vs signed
    rc, _ = run([apk, "--expect-signed"])
    if rc == 0:
        fails.append("an unsigned artifact passed --expect-signed")
    sig_apk = os.path.join(tmp, "app-release-signed.apk")
    make_apk(sig_apk, build_axml(), signed=True)
    rc, out = run([sig_apk, "--expect-signed", "--expect-not-debuggable"])
    if rc != 0:
        fails.append("a signed artifact failed --expect-signed: %s" % out[-300:])

    # 7. an AAB (base/ folder + protobuf manifest) is reported, not crashed on
    aab = os.path.join(tmp, "app-release.aab")
    proto = (b"\x0a\x1e" + b"io.github.mcyber12.opencode" + b"\x12\x10" + b"1.18.23-phase10")
    with zipfile.ZipFile(aab, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("base/manifest/AndroidManifest.xml", proto)
        zf.writestr("base/dex/classes.dex", b"dex\n035\x00")
        zf.writestr("base/assets/runtime-manifest.json", '{"payloadVersion": 7}\n')
        zf.writestr("base/assets/opencode-runtime.tar.gz", b"\x1f\x8b\x08\x00")
        for abi in ("arm64-v8a",):
            zf.writestr("base/lib/%s/libbun.so" % abi, b"\x7fELF")
    rc, out = run([aab, "--expect-native-abi", "arm64-v8a", "--expect-payload",
                   "--expect-package", "io.github.mcyber12.opencode",
                   "--expect-version-name", "1.18.23-phase10"])
    if rc != 0:
        fails.append("AAB survey failed: %s" % out[-300:])
    if "kind=aab" not in out:
        fails.append("AAB not reported as an aab")

    # 8. end-to-end: the int-encoded (real aapt2) label must PASS the full
    # checker, and the literal must FAIL it with the label finding.
    rc, out = run([ref_apk, "--expect-payload", "--expect-not-debuggable"])
    if rc != 0 or "label is a literal" in out:
        fails.append("resource-id label failed the full checker: %s" % out[-200:])
    rc, out = run([lit_apk, "--expect-payload"])
    if rc == 0 or "label is a literal" not in out:
        fails.append("literal label slipped through the full checker (rc=%d)" % rc)

    # 9. the AAPT decompression quirk: a payload stored as assets/*.tar (no .gz)
    # must be FOUND, named and sized in the report (the runtime reads both names).
    tar_apk = os.path.join(tmp, "app-payload-tar.apk")
    make_apk(tar_apk, build_axml(), payload_name="assets/runtime-payload.tar")
    rc, out = run([tar_apk, "--expect-payload"])
    if rc != 0 or "PAYLOAD_ASSET name=runtime-payload.tar" not in out:
        fails.append("a .tar-stored payload was not accepted/identified: %s" % out[-300:])

    # 10. the asset_list diagnostic: every asset entry appears with its size.
    rc, out = run([apk, "--expect-payload"])
    if ("ASSETS 2 entries: runtime-manifest.json" not in out
            or "runtime-payload.tar.gz(" not in out):
        fails.append("asset_list line missing/incomplete: %s" % out[-300:])

    for f in fails:
        print("SELFTEST FAIL %s" % f)
    # one honest total: the parse/label dict checks + the 12 assertions the
    # section runs make (a hardcoded count once drifted from reality by one).
    print("SELFTEST %s (%d checks)" % ("PASS" if not fails else "FAIL",
                                       len(checks) + 12))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
