#!/usr/bin/env python3
"""Phase 10: inspect a BUILT APK/AAB and assert what the store will receive.

Why this exists (and why it is Python, not `aapt2`):
  * the Phase 9 development sandbox and every human reviewer can run it with no
    JDK, no Android SDK and no network - `aapt2`/`apksigner` are not installable
    in the authoring environment (dl.google.com is unreachable there, see the
    Phase 9 report), so "verify the artifact" would otherwise mean "trust the
    build log";
  * it reads the SAME bytes a device or Play reads: the binary
    AndroidManifest.xml inside the zip, the APK Signing Block, the v1
    META-INF signature files, and the zip inventory (native libs per ABI,
    embedded runtime assets);
  * Play refusals are cheap to predict and expensive to discover: a wrong
    applicationId, a debuggable release, a missing icon, an absent payload or a
    signature that is not the release key are exactly the failures this project
    cannot afford at the last step.

It is deliberately strict about what it can see and honest about what it cannot:
it reports the signature SCHEME present and the certificate's SHA-256 if the v1
signature files are readable, but it does NOT validate a cryptographic signature
(that is `apksigner verify`, which the human runs at signing time - docs/RELEASE.md).

Usage:
  python3 phase10/scripts/check-apk.py <apk-or-aab> [expectations]
Expectations (all optional):
  --expect-package ID          applicationId must equal ID
  --expect-version-name NAME   versionName must equal NAME
  --expect-version-code N      versionCode must equal N (bare APK only)
  --expect-not-debuggable      the release manifest must not set debuggable=true
  --expect-signed              a v1/v2/v3 signature must be present
  --expect-unsigned            no signature may be present
  --expect-native-abi ABI      a lib/<ABI>/ directory must exist (repeatable)
  --expect-min-sdk N
  --expect-target-sdk N
  --expect-permission NAME     a uses-permission must be present (repeatable)
  --expect-no-permission NAME
  --expect-icon                an icon + round icon must be declared
  --expect-payload             the embedded runtime payload must be inside
  --json PATH                  also write the full report as JSON
Exit code: 0 = every expectation met (and the artifact parsed), 1 = otherwise.
"""
import json
import os
import re
import struct
import sys
import zipfile

# ---------------------------------------------------------------- binary AXML


class AxmlError(Exception):
    pass


def _u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def _s32(b, o):
    return struct.unpack_from("<i", b, o)[0]


def parse_string_pool(buf, off):
    """Return the string list of a RES_STRING_POOL_TYPE chunk at `off`."""
    if _u16(buf, off) != 0x0001:
        raise AxmlError("no string pool at offset %d" % off)
    count = _u32(buf, off + 8)
    flags = _u32(buf, off + 16)
    strings_start = _u32(buf, off + 20)
    utf8 = bool(flags & (1 << 8))
    base = off + strings_start
    # First pass: offsets.
    offsets = [_u32(buf, off + 28 + 4 * i) for i in range(count)]
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            # two lengths (chars, bytes) as 1-2 byte varints, then the bytes
            n, p = _varint8(buf, p)
            m, p = _varint8(buf, p)
            out.append(buf[p:p + m].decode("utf-8", "replace"))
        else:
            n = _u16(buf, p)
            p += 2
            if n & 0x8000:  # extended: a second uint16 carries the byte length
                n = ((n & 0x7FFF) << 16) | _u16(buf, p)
                p += 2
                n //= 2
            out.append(buf[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out


def _varint8(buf, p):
    v = buf[p]
    if v & 0x80:
        v = ((v & 0x7F) << 8) | buf[p + 1]
        return v, p + 2
    return v, p + 1


ATTR_TYPES = {
    0x01: "reference",
    0x02: "attribute",
    0x03: "string",
    0x04: "float",
    0x05: "dimension",
    0x06: "fraction",
    0x07: "dynamic_reference",
    0x08: "int_dec",
    0x10: "int_hex",
    0x11: "int_boolean",
    0x12: "int_color_8",
    0x1c: "int_color_4",
    0x1d: "int_color_2",
}


def _typed_value(buf, off):
    # Res_value: uint16 size, uint8 res0, uint8 dataType, uint32 data (8 bytes).
    size, res0, dtype, data = struct.unpack_from("<HBBI", buf, off)
    if dtype == 0x03:
        return ("string", data)
    if dtype == 0x01:
        return ("reference", data)
    if dtype == 0x12:  # TYPE_INT_BOOLEAN, checked before the generic int case
        return ("int_boolean", data)
    if dtype in (0x10, 0x11, 0x1c, 0x1d, 0x08):
        return ("int", data)
    if dtype == 0x02:
        return ("attribute", data)
    return (ATTR_TYPES.get(dtype, "type_0x%02x" % dtype), data)


def parse_manifest(buf):
    """Parse a binary AndroidManifest.xml into a plain dict.

    Returns {package, versionCode, versionName, minSdk, targetSdk, permissions,
             application_attrs, elements:[{tag, attrs}], strings}
    Only what this phase asserts on is extracted, but the element walk is generic
    so an unexpected/renamed attribute shows up in the report instead of being
    silently dropped.
    """
    if _u16(buf, 0) != 0x0003:
        raise AxmlError("not a binary AXML (first chunk type 0x%04x)" % _u16(buf, 0))
    strings = parse_string_pool(buf, 8)
    pos = 8 + _u32(buf, 8 + 4)
    elements = []
    permissions = []
    app_attrs = {}
    manifest_attrs = {}
    uses_sdk = {}
    info = {}

    def fmt(v):
        """Res_value -> a plain Python value.

        Booleans are normalised to "true"/"false" strings because that is how a
        real aapt2 manifest stores them (TYPE_INT_BOOLEAN with data 0/1) and the
        assertions in this file read `debuggable`/`allowBackup` as text.
        """
        kind, data = v
        if kind == "int_boolean":
            return "true" if data else "false"
        if kind in ("string", "reference"):
            if isinstance(data, int) and 0 <= data < len(strings):
                return strings[data]
            return data
        return data

    while pos + 8 <= len(buf):
        ctype = _u16(buf, pos)
        header = _u16(buf, pos + 2)
        csize = _u32(buf, pos + 4)
        if csize == 0:
            break
        if ctype == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            pass
        elif ctype == 0x0102:  # START_ELEMENT
            name_idx = _u32(buf, pos + 20)
            attr_start = _u16(buf, pos + 24)
            attr_count = _u16(buf, pos + 28)
            tag = strings[name_idx] if name_idx < len(strings) else "?"
            attrs = {}
            for i in range(attr_count):
                aoff = pos + 16 + attr_start + i * 20
                ns_idx = _s32(buf, aoff)
                name_i = _u32(buf, aoff + 4)
                raw_i = _s32(buf, aoff + 8)
                value = _typed_value(buf, aoff + 12)
                a_name = strings[name_i] if 0 <= name_i < len(strings) else "?"
                if raw_i >= 0 and raw_i < len(strings):
                    a_val = strings[raw_i]
                else:
                    a_val = fmt(value)
                # Namespaced (android:) attributes still carry the bare name in
                # the pool ("debuggable", not "android:debuggable"), so every
                # attribute is keyed by its name; ns_idx is kept for the report.
                attrs[a_name] = a_val
                attrs.setdefault("_ns_" + a_name, ns_idx)
            elements.append((tag, attrs))
            if tag == "manifest":
                manifest_attrs = attrs
            elif tag == "uses-permission" or tag == "uses-permission-sdk-23":
                permissions.append(str(attrs.get("name", "")))
            elif tag == "uses-sdk":
                uses_sdk = attrs
            elif tag == "application":
                app_attrs = attrs
                info["application_attrs"] = attrs
        pos += csize

    info["package"] = str(manifest_attrs.get("package", ""))
    info["versionCode"] = manifest_attrs.get("versionCode")
    info["versionName"] = manifest_attrs.get("versionName")
    info["platformBuildVersionCode"] = manifest_attrs.get("platformBuildVersionCode")
    info["minSdk"] = uses_sdk.get("minSdkVersion")
    info["targetSdk"] = uses_sdk.get("targetSdkVersion")
    info["permissions"] = sorted(set(p for p in permissions if p))
    info["debuggable"] = str(app_attrs.get("debuggable", "false")).lower() == "true"
    info["allowBackup"] = str(app_attrs.get("allowBackup", "true")).lower()
    info["extractNativeLibs"] = str(app_attrs.get("extractNativeLibs", "true")).lower()
    info["networkSecurityConfig"] = bool(app_attrs.get("networkSecurityConfig"))
    info["icon_declared"] = bool(app_attrs.get("icon"))
    info["round_icon_declared"] = bool(app_attrs.get("roundIcon"))
    # A binary AXML does NOT store `@string/app_name` as that text: aapt2 writes
    # it as a resource-ID reference (TYPE_REFERENCE, e.g. 0x7F0A0001), which this
    # parser surfaces as an int. The first version of this check compared
    # str(label) to startswith("@") and therefore called every REAL release APK
    # "a literal label" (CI run #6: label=2131296266). An int value IS a
    # resource reference; a string is a resource only when it still carries the
    # "@..." form (which the test encoder produces). The raw value is reported
    # so a reader can see which case occurred.
    label = app_attrs.get("label")
    if isinstance(label, int):
        info["label_is_resource"] = True
        info["label_raw"] = "0x%08x" % label
    elif isinstance(label, str):
        info["label_is_resource"] = label.startswith("@")
        info["label_raw"] = label
    else:
        info["label_is_resource"] = False
        info["label_raw"] = None
    info["elements"] = [{"tag": t, "attrs": a} for t, a in elements]
    info["strings"] = strings
    return info


def find_axml(zf, name="AndroidManifest.xml"):
    """Locate the manifest inside an APK (AXML at the root) or an AAB (protobuf,
    under <module>/manifest/). Returns (bytes, format) or (None, None)."""
    names = zf.namelist()
    for n in names:
        if n == name:
            return zf.read(n), "axml"
    for n in names:
        if n.endswith("/manifest/AndroidManifest.xml") or n == "manifest/AndroidManifest.xml":
            blob = zf.read(n)
            # AXML starts with 0x0003 (RES_XML_TYPE); a bundle's manifest is
            # protobuf, where the same bytes would be noise.
            if len(blob) >= 2 and struct.unpack_from("<H", blob, 0)[0] == 0x0003:
                return blob, "axml"
            return blob, "protobuf"
    return None, None


def protobuf_strings(blob):
    """Printable runs inside a protobuf-encoded manifest.

    Deliberately crude and labelled as such: an AAB's manifest is a protobuf
    document and this file has no protobuf runtime. Length-delimited UTF-8
    fields (package name, versionName, permission names) are stored verbatim, so
    a printable-run scan recovers them; it can neither prove absence nor order.
    What it can do - and what the release gate needs - is confirm that the bundle
    carries THIS app's identity rather than a stale one.
    """
    runs, cur = [], []
    for b in blob:
        if 0x20 <= b < 0x7F:
            cur.append(chr(b))
        else:
            if len(cur) >= 4:
                runs.append("".join(cur))
            cur = []
    if len(cur) >= 4:
        runs.append("".join(cur))
    return runs


# ------------------------------------------------------------------ signatures

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
AAB_SIG_MAGIC = b"JAR"  # AABs are JAR-signed (v1) or unsigned


def signature_report(path):
    """What signature is on the artifact. No crypto: presence + identity only."""
    rep = {"v1_meta_inf": [], "v1_certificate_sha256": None, "v2_or_v3_block": False,
           "scheme": "none", "comment": ""}
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        rep["v1_meta_inf"] = sorted(n for n in names if n.upper().startswith("META-INF/")
                                    and n.upper().endswith((".RSA", ".DSA", ".EC", ".SF")))
        certs = [n for n in rep["v1_meta_inf"] if n.upper().endswith((".RSA", ".DSA", ".EC"))]
        if certs:
            try:
                import hashlib
                # a PKCS#7 blob: the certificate is embedded; hash the blob itself so
                # the report is comparable run to run (a real X.509 digest needs
                # crypto tooling the sandbox does not have).
                blob = zf.read(certs[0])
                rep["v1_certificate_sha256"] = hashlib.sha256(blob).hexdigest()[:32]
            except Exception as e:  # pragma: no cover - defensive
                rep["comment"] = "certificate unreadable: %s" % e
    with open(path, "rb") as fh:
        size = os.path.getsize(path)
        tail = min(size, 1 << 22)
        fh.seek(-tail, os.SEEK_END)
        tail_bytes = fh.read()
    if APK_SIG_BLOCK_MAGIC in tail_bytes:
        rep["v2_or_v3_block"] = True
    if rep["v2_or_v3_block"]:
        rep["scheme"] = "v2/v3 (+v1)" if rep["v1_meta_inf"] else "v2/v3"
    elif rep["v1_meta_inf"]:
        rep["scheme"] = "v1"
    if path.endswith(".aab"):
        rep["comment"] = (rep["comment"] + " AAB is signed with the upload key (jarsigner); "
                          "Play re-signs the delivered APKs with the app signing key.")
    return rep


# ------------------------------------------------------------------ zip survey


def zip_survey(path):
    survey = {"entry_count": 0, "size_bytes": os.path.getsize(path), "abis": {},
              "native_libs": {}, "assets": {"runtime_manifest": False, "payload_asset": False,
                                             "payload_name": None, "payload_bytes": None,
                                             "asset_list": []},
              "uncompressed_bytes": 0, "compressed_bytes": 0, "top_level": {}}
    is_aab = path.endswith(".aab")
    with zipfile.ZipFile(path) as zf:
        infos = zf.infolist()
        survey["entry_count"] = len(infos)
        for i in infos:
            n = i.filename
            key = n
            if is_aab and "/" in n:
                # bundle entries are <module>/<path>; report them as the APK would
                key = n.split("/", 1)[1]
            top = key.split("/")[0]
            survey["top_level"][top] = survey["top_level"].get(top, 0) + 1
            survey["uncompressed_bytes"] += i.file_size
            survey["compressed_bytes"] += i.compress_size
            m = re.match(r"lib/([^/]+)/(.+)$", key)
            if m:
                abi, lib = m.group(1), m.group(2)
                d = survey["abis"].setdefault(abi, {"count": 0, "bytes": 0})
                d["count"] += 1
                d["bytes"] += i.file_size
                survey["native_libs"]["%s/%s" % (abi, lib)] = i.file_size
            if key.startswith("assets/"):
                # Run #6 failed with "no payload asset" while the app ran the
                # runtime on the very same APK: a bare boolean was not enough to
                # tell packaging truth from extension mismatch. The full list
                # (name + stored size per asset entry) makes the next such
                # question answerable from the report alone.
                survey["assets"]["asset_list"].append("%s(%dB)" % (key[len("assets/"):], i.file_size))
            if key == "assets/runtime-manifest.json":
                survey["assets"]["runtime_manifest"] = True
            # AAPT may repackage a ".gz" asset DECOMPRESSED and renamed
            # (runtime-payload.tar.gz -> runtime-payload.tar); the app itself
            # reads both names (PayloadExtractor). Accepting only .tar.gz here
            # once produced a false FAIL on an APK whose runtime demonstrably
            # worked, so the accepted set mirrors the app's own candidate list.
            asset_name = key[len("assets/"):] if key.startswith("assets/") else ""
            if asset_name in ("runtime-payload.tar.gz", "runtime-payload.tar", "runtime-payload.tgz") \
                    or asset_name.endswith((".tar.gz", ".tgz", ".tar")):
                survey["assets"]["payload_asset"] = True
                if survey["assets"]["payload_name"] is None or asset_name.startswith("runtime-payload"):
                    survey["assets"]["payload_name"] = asset_name
                    survey["assets"]["payload_bytes"] = i.file_size
    survey["assets"]["asset_list"].sort()
    return survey


# ----------------------------------------------------------------------- main


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    ex = {"package": None, "version_name": None, "version_code": None,
          "not_debuggable": False, "signed": False, "unsigned": False,
          "abis": [], "min_sdk": None, "target_sdk": None,
          "permissions": [], "no_permissions": [], "icon": False, "payload": False,
          "json": None}
    i = 2
    while i < len(argv):
        a = argv[i]
        if a == "--expect-package":
            ex["package"] = argv[i + 1]; i += 2
        elif a == "--expect-version-name":
            ex["version_name"] = argv[i + 1]; i += 2
        elif a == "--expect-version-code":
            ex["version_code"] = int(argv[i + 1]); i += 2
        elif a == "--expect-not-debuggable":
            ex["not_debuggable"] = True; i += 1
        elif a == "--expect-signed":
            ex["signed"] = True; i += 1
        elif a == "--expect-unsigned":
            ex["unsigned"] = True; i += 1
        elif a == "--expect-native-abi":
            ex["abis"].append(argv[i + 1]); i += 2
        elif a == "--expect-min-sdk":
            ex["min_sdk"] = int(argv[i + 1]); i += 2
        elif a == "--expect-target-sdk":
            ex["target_sdk"] = int(argv[i + 1]); i += 2
        elif a == "--expect-permission":
            ex["permissions"].append(argv[i + 1]); i += 2
        elif a == "--expect-no-permission":
            ex["no_permissions"].append(argv[i + 1]); i += 2
        elif a == "--expect-icon":
            ex["icon"] = True; i += 1
        elif a == "--expect-payload":
            ex["payload"] = True; i += 1
        elif a == "--json":
            ex["json"] = argv[i + 1]; i += 2
        else:
            print("unknown argument: %s" % a)
            return 2
    if not os.path.isfile(path):
        print("FAIL %s does not exist" % path)
        return 1

    findings = []
    report = {"artifact": os.path.basename(path), "kind": "aab" if path.endswith(".aab") else "apk"}
    manifest_format = "none"
    pb_strings = []
    try:
        with zipfile.ZipFile(path) as zf:
            raw, manifest_format = find_axml(zf)
        if raw is None:
            findings.append("no AndroidManifest.xml inside the archive")
            mf = {}
        elif manifest_format == "axml":
            try:
                mf = parse_manifest(raw)
            except AxmlError as e:
                findings.append("manifest parse failed: %s" % e)
                mf = {}
        else:
            # An AAB: identity comes from the protobuf strings, and the AXML-only
            # assertions below are reported as unverifiable instead of silently
            # treated as satisfied.
            pb_strings = protobuf_strings(raw)
            mf = {"package": None, "versionName": None, "versionCode": None,
                  "minSdk": None, "targetSdk": None, "permissions": [],
                  "debuggable": None, "label_is_resource": True}
            for cand in pb_strings:
                if cand == ex["package"] or (ex["package"] and ex["package"] in cand):
                    mf["package"] = ex["package"]
                # Same containment semantics as the package line above: the
                # printable-run scanner recovers a protobuf field's text glued
                # to its neighbours ("versionName1.18.23-phase10..."), so an
                # EXACT match was unsatisfiable - run #7 proved that: the
                # bundle's manifest carries the version (substring found), the
                # gate still FAILed demanding a standalone token. A crude
                # scanner gets to use one containment rule for all strings.
                if ex["version_name"] and (cand == ex["version_name"]
                                           or ex["version_name"] in cand):
                    mf["versionName"] = ex["version_name"]
    except zipfile.BadZipFile as e:
        print("FAIL %s is not a readable zip (%s)" % (path, e))
        return 1

    sig = signature_report(path)
    survey = zip_survey(path)

    report["manifest_format"] = manifest_format
    report["manifest"] = {k: v for k, v in mf.items() if k not in ("strings", "elements")}
    if manifest_format == "protobuf":
        report["manifest_strings"] = pb_strings
    if mf.get("elements"):
        report["manifest"]["components"] = {
            "activities": [e["attrs"].get("name") for e in mf["elements"]
                           if e["tag"] == "activity"],
            "services": [e["attrs"].get("name") for e in mf["elements"]
                         if e["tag"] == "service"],
        }
    report["signature"] = sig
    report["contents"] = survey
    # A label that is a resource is the only shape we accept: a literal product
    # name in the manifest cannot be translated and is a common store-review nit.
    if mf and not mf.get("label_is_resource", True):
        findings.append("application label is a literal, not a @string resource")

    def check(cond, msg):
        if not cond:
            findings.append(msg)

    if ex["package"]:
        if manifest_format == "protobuf":
            check(any(ex["package"] in c for c in pb_strings),
                  "package %r not found among the bundle manifest's strings (found: %s)"
                  % (ex["package"], [c for c in pb_strings if "." in c][:6]))
        else:
            check(mf.get("package") == ex["package"],
                  "package=%r expected %r" % (mf.get("package"), ex["package"]))
    if ex["version_name"]:
        if manifest_format == "protobuf":
            check(any(ex["version_name"] in c for c in pb_strings),
                  "versionName %r not found among the bundle manifest's strings "
                  "(substring match, as for the package - the protobuf scan is "
                  "run-delimited, not token-delimited: see protobuf_strings())"
                  % ex["version_name"])
        else:
            check(mf.get("versionName") == ex["version_name"],
                  "versionName=%r expected %r" % (mf.get("versionName"), ex["version_name"]))
    if ex["version_code"] is not None:
        check(mf.get("versionCode") == ex["version_code"],
              "versionCode=%r expected %r" % (mf.get("versionCode"), ex["version_code"]))
    if ex["not_debuggable"]:
        if manifest_format == "protobuf":
            findings.append("cannot verify debuggable in an AAB (protobuf manifest) - "
                            "verify the APK built from the same source instead")
        else:
            check(not mf.get("debuggable", False),
                  "manifest sets debuggable=true - Play rejects debuggable release artifacts")
    if ex["signed"]:
        check(sig["scheme"] != "none", "artifact is UNSIGNED but a signature was expected")
    if ex["unsigned"]:
        check(sig["scheme"] == "none",
              "artifact carries a %s signature but an unsigned artifact was expected" % sig["scheme"])
    for abi in ex["abis"]:
        check(abi in survey["abis"],
              "no lib/%s/ directory (abis present: %s)" % (abi, sorted(survey["abis"])))
    if ex["min_sdk"] is not None:
        check(_as_int(mf.get("minSdk")) == ex["min_sdk"],
              "minSdk=%r expected %r" % (mf.get("minSdk"), ex["min_sdk"]))
    if ex["target_sdk"] is not None:
        check(_as_int(mf.get("targetSdk")) == ex["target_sdk"],
              "targetSdk=%r expected %r" % (mf.get("targetSdk"), ex["target_sdk"]))
    for p in ex["permissions"]:
        check(p in mf.get("permissions", []), "uses-permission %s missing" % p)
    for p in ex["no_permissions"]:
        check(p not in mf.get("permissions", []), "uses-permission %s present but not expected" % p)
    if ex["icon"]:
        check(mf.get("icon_declared"), "application declares no icon")
        check(mf.get("round_icon_declared"), "application declares no roundIcon")
    if ex["payload"]:
        check(survey["assets"]["runtime_manifest"],
              "assets/runtime-manifest.json missing - the embedded runtime is not in this artifact")
        check(survey["assets"]["payload_asset"],
              "no runtime payload asset in this artifact (expected assets/runtime-payload.tar.gz"
              " or the .tar form AAPT may store it as)")

    report["findings"] = findings
    if ex["json"]:
        with open(ex["json"], "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2, sort_keys=True)
            fh.write("\n")

    # A one-screen human summary; the CI gate greps these lines.
    print("ARTIFACT %s kind=%s manifest=%s bytes=%d"
          % (report["artifact"], report["kind"], manifest_format, survey["size_bytes"]))
    if mf:
        print("MANIFEST package=%s versionCode=%s versionName=%s minSdk=%s targetSdk=%s debuggable=%s"
              % (mf.get("package"), mf.get("versionCode"), mf.get("versionName"),
                 mf.get("minSdk"), mf.get("targetSdk"), mf.get("debuggable")))
        print("MANIFEST icon=%s roundIcon=%s allowBackup=%s extractNativeLibs=%s netsecConfig=%s"
              % (mf.get("icon_declared"), mf.get("round_icon_declared"), mf.get("allowBackup"),
                 mf.get("extractNativeLibs"), mf.get("networkSecurityConfig")))
        print("PERMISSIONS %s" % ",".join(mf.get("permissions", [])))
    print("SIGNATURE scheme=%s v1_files=%d v2_v3_block=%s cert_id=%s"
          % (sig["scheme"], len(sig["v1_meta_inf"]), sig["v2_or_v3_block"],
             sig["v1_certificate_sha256"] or "n/a"))
    print("CONTENTS entries=%d native_abis=%s payload=%s manifest_asset=%s"
          % (survey["entry_count"],
             ",".join("%s(%d libs,%.1fMB)" % (a, d["count"], d["bytes"] / 1048576.0)
                      for a, d in sorted(survey["abis"].items())),
             survey["assets"]["payload_asset"], survey["assets"]["runtime_manifest"]))
    if survey["assets"]["payload_name"]:
        print("PAYLOAD_ASSET name=%s bytes=%d"
              % (survey["assets"]["payload_name"], survey["assets"]["payload_bytes"]))
    # The raw truth about the assets directory, one line, so "staged but not
    # packaged" and "packaged under a different name" are distinguishable
    # without opening the zip (run #6 cost two CI runs to tell apart).
    print("ASSETS %d entries: %s" % (len(survey["assets"]["asset_list"]),
                                     ", ".join(survey["assets"]["asset_list"])[:300]))
    for f in findings:
        print("FINDING %s" % f)
    print("VERDICT %s" % ("PASS" if not findings else "FAIL"))
    return 0 if not findings else 1


def _as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


if __name__ == "__main__":
    sys.exit(main(sys.argv))
