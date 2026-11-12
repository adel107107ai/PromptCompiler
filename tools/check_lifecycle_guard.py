#!/usr/bin/env python3
"""Fail when a screen posts UI work from a background thread but cannot tell it is still alive.

Adding a window to an activity that is finishing or destroyed throws
WindowManager$BadTokenException - a crash the user sees as "it closed on its own". Every screen
that posts work back from a thread must therefore be able to test isFinishing()/isDestroyed().
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "src" / "com" / "promptengine" / "app"

# Posting from a thread is the marker for work that can outlive the screen.
POSTS = ("runOnUiThread(", "MAIN.post(", ".post(new Runnable")


def main():
    problems = []
    checked = 0
    for path in sorted(SRC.glob("*Activity.java")):
        text = path.read_text(encoding="utf-8")
        if not any(marker in text for marker in POSTS):
            continue
        checked += 1
        if "isFinishing()" not in text:
            problems.append(
                "%s posts UI work from a thread but never checks isFinishing()/isDestroyed(): "
                "a late callback will try to add a window to a dead activity and crash"
                % path.name)

    if problems:
        for problem in problems:
            print("check_lifecycle_guard: %s" % problem, file=sys.stderr)
        return 1
    print("check_lifecycle_guard: %d files post UI work and all guard against a dead screen"
          % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
