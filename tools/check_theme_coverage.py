#!/usr/bin/env python3
"""Fail when a themeable colour is missing from a theme, or used without being declared.

Every look must define every c_* attribute. A view that asks for an attribute its theme does not
define throws Resources$NotFoundException while inflating, which is a crash, not a wrong colour.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VALUES = ROOT / "android" / "res" / "values"
RES = ROOT / "android" / "res"

THEMES = ("Theme.A", "Theme.B", "Theme.C")


def main():
    problems = []

    attrs_text = (VALUES / "attrs.xml").read_text(encoding="utf-8")
    declared = set(re.findall(r'<attr name="(c_[a-z_0-9]+)"', attrs_text))
    if not declared:
        problems.append("attrs.xml declares no c_* colour attributes")

    themes_text = (VALUES / "themes.xml").read_text(encoding="utf-8")
    for theme in THEMES:
        block = re.search(r'<style name="%s"[^>]*>(.*?)</style>' % re.escape(theme),
                          themes_text, re.DOTALL)
        if not block:
            problems.append("themes.xml has no style named %s" % theme)
            continue
        defined = set(re.findall(r'<item name="(c_[a-z_0-9]+)"', block.group(1)))
        missing = sorted(declared - defined)
        if missing:
            problems.append("%s does not define: %s" % (theme, ", ".join(missing)))

    used = set()
    for path in list((RES / "drawable").glob("*.xml")) + list((RES / "layout").glob("*.xml")) \
            + [VALUES / "styles.xml"]:
        used.update(re.findall(r'\?attr/(c_[a-z_0-9]+)', path.read_text(encoding="utf-8")))
    undeclared = sorted(used - declared)
    if undeclared:
        problems.append("used but not declared in attrs.xml: %s" % ", ".join(undeclared))

    if problems:
        for problem in problems:
            print("check_theme_coverage: %s" % problem, file=sys.stderr)
        return 1
    print("check_theme_coverage: %d colour attributes defined in all %d looks and used safely"
          % (len(declared), len(THEMES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
