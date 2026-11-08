#!/usr/bin/env python3
"""Generate SystemPrompt.java from the canonical Python source.

The prompt policy lives in exactly one place: prompt_compiler/prompt_engine.py.
This script derives the Android constant from it so the two can never drift.

Usage:
    python3 tools/gen_java_prompt.py           # (re)write the Java file
    python3 tools/gen_java_prompt.py --check   # fail if the file is out of date
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from prompt_compiler.catalog import KNOWN_CATEGORIES  # noqa: E402
from prompt_compiler.prompt_engine import SYSTEM_PROMPT  # noqa: E402

TARGET = ROOT / "android" / "src" / "com" / "promptengine" / "app" / "SystemPrompt.java"


def java_string(text):
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif 32 <= ord(ch) < 127:
            out.append(ch)
        else:
            out.append("\\u%04x" % ord(ch))
    return "".join(out)


def render():
    categories = sorted(KNOWN_CATEGORIES)
    literal = "+".join(
        '        "%s\\n"\n' % java_string(line)
        for line in SYSTEM_PROMPT.split("\n")
    )[:-1]
    cat_literal = ", ".join('"%s"' % c for c in categories)
    return (
        "package com.promptengine.app;\n"
        "\n"
        "/**\n"
        " * GENERATED FILE - do not edit by hand.\n"
        " * Source: prompt_compiler/prompt_engine.py (SYSTEM_PROMPT)\n"
        " * Regenerate: python3 tools/gen_java_prompt.py\n"
        " */\n"
        "public final class SystemPrompt {\n"
        "\n"
        "    private SystemPrompt() {\n"
        "    }\n"
        "\n"
        "    public static final String TEXT =\n"
        f"{literal};\n"
        "\n"
        "    public static final String[] KNOWN_CATEGORIES = {%s};\n"
        "}\n" % cat_literal
    )


def main(argv):
    content = render()
    check = "--check" in argv
    if check:
        if not TARGET.exists():
            print("gen_java_prompt: %s is missing" % TARGET, file=sys.stderr)
            return 1
        if TARGET.read_text(encoding="utf-8") != content:
            print(
                "gen_java_prompt: %s is out of date - run python3 tools/gen_java_prompt.py"
                % TARGET,
                file=sys.stderr,
            )
            return 1
        print("gen_java_prompt: SystemPrompt.java is in sync with prompt_engine.py")
        return 0

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(content, encoding="utf-8")
    print("gen_java_prompt: wrote %s (%d chars of prompt)" % (TARGET, len(SYSTEM_PROMPT)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
