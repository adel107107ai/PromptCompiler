#!/usr/bin/env python3
"""Fail when an activity can rebuild itself forever over a theme change.

A screen that compares the stored look with the one it was built with, calls recreate() on a
difference, but never applies the theme (or never records what it applied) rebuilds itself in a
loop: the screen flickers endlessly and the app is unusable. That exact defect shipped once, so
it is checked here.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "src" / "com" / "promptengine" / "app"


def strip_comments(text):
    """Comments must not influence the checks: a comment mentioning recreate() is not a call."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", text)


def main():
    problems = []
    checked = 0
    for path in sorted(SRC.glob("*Activity.java")):
        text = strip_comments(path.read_text(encoding="utf-8"))
        uses_look = "appliedLook" in text
        recreates = "recreate()" in text
        if not uses_look and not recreates:
            continue
        checked += 1

        if uses_look and "appliedLook = ThemePreset.apply(this);" not in text:
            problems.append(
                "%s tracks appliedLook but never applies the theme in onCreate: the stored look "
                "can never match, so any recreate() repeats forever" % path.name)

        # On the recreate path the recorded look must be updated before rebuilding, otherwise a
        # screen that did not apply the theme on create loops.
        for match in re.finditer(r"recreate\(\)", text):
            window = text[max(0, match.start() - 400):match.start()]
            if "appliedLook" in window and not re.search(r"appliedLook\s*=", window):
                problems.append(
                    "%s calls recreate() without recording the applied look first" % path.name)
                break

    if problems:
        for problem in problems:
            print("check_theme_apply: %s" % problem, file=sys.stderr)
        return 1
    print("check_theme_apply: %d activities handle a look change without rebuild loops" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
