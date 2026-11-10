#!/usr/bin/env python3
"""Generate SystemPrompt.java from the canonical Python source.

The prompt policy lives in exactly one place: prompt_compiler/prompt_engine.py.
This script derives the Android constants from it so the two can never drift.

Usage:
    python3 tools/gen_java_prompt.py           # (re)write the Java file
    python3 tools/gen_java_prompt.py --check   # fail if the file is out of date
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from prompt_compiler.catalog import KNOWN_CATEGORIES  # noqa: E402
from prompt_compiler.prompt_engine import (  # noqa: E402
    ANSWERS_HEADER,
    CLARIFY_SYSTEM_PROMPT,
    MAX_CLARIFY_QUESTIONS,
    REQUEST_PREFIX,
    SYSTEM_PROMPT,
)

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


def java_literal(text, indent="        "):
    """A Java string constant, split per line so the source stays readable."""
    lines = text.split("\n")
    parts = []
    for index, line in enumerate(lines):
        suffix = "\\n" if index < len(lines) - 1 else ""
        parts.append('%s"%s%s"' % (indent, java_string(line), suffix))
    return "\n+".join(parts)


def render():
    categories = sorted(KNOWN_CATEGORIES)
    cat_literal = ", ".join('"%s"' % c for c in categories)
    return (
        "package com.promptengine.app;\n"
        "\n"
        "/**\n"
        " * GENERATED FILE - do not edit by hand.\n"
        " * Source: prompt_compiler/prompt_engine.py\n"
        " * Regenerate: python3 tools/gen_java_prompt.py\n"
        " */\n"
        "public final class SystemPrompt {\n"
        "\n"
        "    private SystemPrompt() {\n"
        "    }\n"
        "\n"
        "    /** Compile phase: the prompt policy that produces the final artifact. */\n"
        "    public static final String TEXT =\n"
        f"{java_literal(SYSTEM_PROMPT)};\n"
        "\n"
        "    /** Discovery phase: decides what is still unknown before anything is written. */\n"
        "    public static final String CLARIFY_TEXT =\n"
        f"{java_literal(CLARIFY_SYSTEM_PROMPT)};\n"
        "\n"
        "    /** Wrapper placed before the user's request in the compile phase. */\n"
        "    public static final String REQUEST_PREFIX =\n"
        f"{java_literal(REQUEST_PREFIX)};\n"
        "\n"
        "    /** Header placed before the answers collected by the discovery phase. */\n"
        "    public static final String ANSWERS_HEADER =\n"
        f"{java_literal(ANSWERS_HEADER)};\n"
        "\n"
        f"    public static final int MAX_CLARIFY_QUESTIONS = {MAX_CLARIFY_QUESTIONS};\n"
        "\n"
        "    public static final String[] KNOWN_CATEGORIES = {%s};\n"
        "}\n" % (cat_literal,)
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
    print(
        "gen_java_prompt: wrote %s (compile %d chars, clarify %d chars)"
        % (TARGET, len(SYSTEM_PROMPT), len(CLARIFY_SYSTEM_PROMPT))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
