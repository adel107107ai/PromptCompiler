#!/usr/bin/env python3
"""Fail if an APK breaks the targetSdk >= 30 packaging rules.

resources.arsc must be stored (uncompressed) and its data must start on a 4-byte
boundary; the same boundary applies to every other stored entry.

The data offset has to be computed from the LOCAL header, because a signer pads
the local header's extra field for alignment while the central directory keeps the
original (shorter) extra field. Reading the central directory extra here reports
offsets that are wrong by several bytes.

zipalign itself cannot be used to verify this on the phone: the SDK build-tools
zipalign is an x86 binary.

Usage: python3 tools/check_apk_alignment.py <apk>
"""

import struct
import sys
import zipfile

LOCAL_HEADER = 0x04034B50


def local_data_offset(handle, info, path):
    handle.seek(info.header_offset)
    header = handle.read(30)
    if len(header) != 30:
        raise ValueError("truncated local header for %s" % info.filename)
    fields = struct.unpack("<IHHHHHIIIHH", header)
    if fields[0] != LOCAL_HEADER:
        raise ValueError("bad local header signature for %s in %s" % (info.filename, path))
    name_length, extra_length = fields[9], fields[10]
    return info.header_offset + 30 + name_length + extra_length


def main(argv):
    if len(argv) != 1:
        print("usage: check_apk_alignment.py <apk>", file=sys.stderr)
        return 2
    path = argv[0]
    problems = []
    found_arsc = False

    with zipfile.ZipFile(path) as apk, open(path, "rb") as handle:
        entries = apk.infolist()
        for info in entries:
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            try:
                offset = local_data_offset(handle, info, path)
            except (ValueError, OSError) as failure:
                problems.append(str(failure))
                continue
            if offset % 4 != 0:
                problems.append(
                    "%s is stored but its data starts at %d, which is not 4-byte aligned"
                    % (info.filename, offset))
            if info.filename == "resources.arsc":
                found_arsc = True

        for info in entries:
            if info.filename == "resources.arsc" and info.compress_type != zipfile.ZIP_STORED:
                problems.append("resources.arsc is compressed; it must be stored")

        if not found_arsc:
            problems.append("resources.arsc is missing from the APK")
        if not any(entry.filename.endswith(".dex") for entry in entries):
            problems.append("no classes.dex in the APK")

    if problems:
        for problem in problems:
            print("check_apk_alignment: %s" % problem, file=sys.stderr)
        return 1
    print("check_apk_alignment: %s is packaged correctly" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
