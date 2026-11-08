#!/usr/bin/env python3
"""Fail if an APK breaks the targetSdk >= 30 packaging rules.

resources.arsc must be stored (uncompressed) and its data must start on a
4-byte boundary, otherwise Android 11+ rejects or penalises the package.
zipalign cannot be used here: the SDK build-tools zipalign is an x86 binary.

Usage: python3 tools/check_apk_alignment.py <apk>
"""

import sys
import zipfile


def main(argv):
    if len(argv) != 1:
        print("usage: check_apk_alignment.py <apk>", file=sys.stderr)
        return 2
    path = argv[0]
    with zipfile.ZipFile(path) as apk:
        problems = []
        found_arsc = False
        for entry in apk.infolist():
            if entry.filename == "resources.arsc":
                found_arsc = True
                if entry.compress_type != zipfile.ZIP_STORED:
                    problems.append("resources.arsc is compressed; it must be stored")
                data_offset = entry.header_offset + 30 + len(entry.filename.encode()) + len(entry.extra)
                if data_offset % 4 != 0:
                    problems.append(
                        "resources.arsc data starts at %d, which is not 4-byte aligned"
                        % data_offset)
        if not found_arsc:
            problems.append("resources.arsc is missing from the APK")
        if not any(name.endswith(".dex") for name in apk.namelist()):
            problems.append("no classes.dex in the APK")

    if problems:
        for problem in problems:
            print("check_apk_alignment: %s" % problem, file=sys.stderr)
        return 1
    print("check_apk_alignment: %s is packaged correctly" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
