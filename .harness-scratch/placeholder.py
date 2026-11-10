#!/usr/bin/env python3
"""Interview spec (1.4.0.md) conformance — replaces the old /clarify route with /analyze."""

import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent
old = REPO / "prompt_compiler" / "api" / "routes" / "clarify.py"
new = REPO / "prompt_compiler" / "api" / "routes" / "analyze.py"
old.unlink()
print("removed clarify.py")
print("analyze.py next")
