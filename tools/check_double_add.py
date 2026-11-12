#!/usr/bin/env python3
"""Fail when a Java view helper adds its own view and is then wrapped in addView() again.

That pattern throws at runtime:

    IllegalStateException: The specified child already has a parent.

The cost of missing it is a crash the user sees and the compiler does not, so it is
checked here instead. Usage: python3 tools/check_double_add.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "src" / "com" / "promptengine" / "app"

# A method body that adds the view it also returns is "self-adding".
METHOD = re.compile(
    r"private\s+(?:static\s+)?[\w<>\[\].]+\s+(\w+)\s*\(([^)]*)\)\s*\{", re.MULTILINE)


def self_adding_methods(text):
    """Names of methods whose first parameter receives an addView of a locally created view."""
    names = set()
    for match in METHOD.finditer(text):
        name = match.group(1)
        params = match.group(2)
        first = params.split(",")[0].strip()
        container = first.split()[-1] if first else ""
        if not container or container in ("int", "String", "Context"):
            continue
        # Walk the body by brace balance from the opening brace.
        start = match.end() - 1
        depth = 0
        end = start
        for index in range(start, len(text)):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index
                    break
        body = text[start:end]
        if container + ".addView(" in body:
            names.add(name)
    return names


def main():
    problems = []
    for path in sorted(SRC.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        helpers = self_adding_methods(text)
        if not helpers:
            continue
        for name in sorted(helpers):
            wrapped = re.findall(r"addView\(\s*" + re.escape(name) + r"\s*\(", text)
            for _ in wrapped:
                problems.append(
                    "%s: addView(%s(...)) - %s already adds the view; this throws "
                    "IllegalStateException at runtime" % (path.name, name, name))
    if problems:
        for problem in problems:
            print("check_double_add: %s" % problem, file=sys.stderr)
        return 1
    # Confirm every activity is writable before reporting a pass.
    writable = all(p.read_text(encoding="utf-8") for p in sorted(SRC.glob("*.java")))
    print("check_double_add: no self-adding helper is wrapped in addView (%d files read)"
          % (len(list(SRC.glob('*.java'))) if writable else 0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
