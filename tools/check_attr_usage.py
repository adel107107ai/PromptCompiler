#!/usr/bin/env python3
"""Fail when a theme attribute is handed to an API that expects a resolved colour.

?attr/c_* is an attribute id, not a colour. Passing it to Context.getColor() or to a setTextColor
style API throws Resources$NotFoundException while the screen is being built - which the user sees
as a blank window and then a crash. Colours must go through Ui.color(context, attribute).
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "src" / "com" / "promptengine" / "app"

# Calls whose argument must be a colour int, never an attribute id.
COLOUR_APIS = {
    "getColor", "setTextColor", "setHintTextColor", "setBackgroundColor",
    "setLinkTextColor", "setHighlightColor", "setColor", "ColorDrawable",
    "setStroke", "setColorFilter", "setTint",
}
RESOLVER = "Ui.color"


def enclosing_call(text, position):
    """The name of the call whose argument list contains `position`, or None."""
    depth = 0
    index = position - 1
    while index >= 0:
        char = text[index]
        if char == ")":
            depth += 1
        elif char == "(":
            if depth == 0:
                match = re.search(r"([A-Za-z_][A-Za-z_0-9.]*)\s*$", text[:index])
                return match.group(1) if match else None
            depth -= 1
        index -= 1
    return None


def strip_noise(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", text)


def main():
    problems = []
    checked = 0
    for path in sorted(SRC.glob("*.java")):
        text = strip_noise(path.read_text(encoding="utf-8"))
        for match in re.finditer(r"R\.attr\.c_[a-z_0-9]+", text):
            call = enclosing_call(text, match.start())
            if call is None:
                continue
            checked += 1
            leaf = call.split(".")[-1]
            if leaf == RESOLVER:
                continue
            if leaf in COLOUR_APIS:
                line = text.count("\n", 0, match.start()) + 1
                problems.append("%s:%d passes %s to %s(), which needs a colour int - use %s()"
                                % (path.name, line, match.group(0), leaf, RESOLVER))

    if problems:
        for problem in problems:
            print("check_attr_usage: %s" % problem, file=sys.stderr)
        return 1
    print("check_attr_usage: %d theme attributes all resolved before reaching a colour API"
          % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
