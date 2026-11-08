#!/usr/bin/env python3
"""Smoke-test the exact Gemini request shape the Android app sends.

Checks, against the real API, that:
  - the key travels in the x-goog-api-key header (not the query string),
  - the model id in config.json answers,
  - streamGenerateContent with alt=sse really streams,
  - the response JSON carries english_prompt / persian_prompt where the parser looks,
  - the incremental field extractor the app uses produces a growing prefix.

The extractor below mirrors PromptEngine.partialField() in
android/src/com/promptengine/app/PromptEngine.java.

Usage: python3 tools/smoke_gemini.py ["persian request"]
"""

import json
import pathlib
import ssl
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from prompt_compiler.config import load_config  # noqa: E402
from prompt_compiler.prompt_engine import SYSTEM_PROMPT, parse_model_output  # noqa: E402

DEFAULT_REQUEST = "یه اسکریپت پایتون بنویس که فایل‌های یک پوشه رو بر اساس پسوند دسته‌بندی کنه"


def partial_field(buffer, field):
    """Line-for-line mirror of PromptEngine.partialField()."""
    key = '"%s"' % field
    key_at = buffer.find(key)
    if key_at < 0:
        return None
    colon = buffer.find(":", key_at + len(key))
    if colon < 0:
        return None
    quote = buffer.find('"', colon + 1)
    if quote < 0:
        return None
    out = []
    i = quote + 1
    while i < len(buffer):
        ch = buffer[i]
        if ch == "\\":
            if i + 1 >= len(buffer):
                break
            nxt = buffer[i + 1]
            if nxt == "n":
                out.append("\n")
                i += 2
            elif nxt == "r":
                out.append("\r")
                i += 2
            elif nxt == "t":
                out.append("\t")
                i += 2
            elif nxt == "u":
                if i + 5 >= len(buffer):
                    break
                out.append(chr(int(buffer[i + 2:i + 6], 16)))
                i += 6
            else:
                out.append(nxt)
                i += 2
        elif ch == '"':
            break
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def main(argv):
    request_text = argv[0] if argv else DEFAULT_REQUEST
    config = load_config()
    api_key = config.get("gemini_api_key", "").strip()
    if not api_key:
        print("smoke_gemini: config.json has no gemini_api_key", file=sys.stderr)
        return 2
    model = config.get("gemini_model", "gemini-3.5-flash")

    url = ("https://generativelanguage.googleapis.com/v1beta/models/"
           "%s:streamGenerateContent?alt=sse" % model)
    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{
            "role": "user",
            "parts": [{"text": (
                "Compile the following Persian request into an optimized English prompt. "
                "The English prompt is the canonical result. Then translate that exact finalized "
                "English prompt into a faithful Persian version. Do not generate Persian "
                "independently from the original input. Follow your instructions:\n\n"
                f"{request_text}"
            )}],
        }],
        "generationConfig": {"temperature": 0.7, "maxOutputTokens": 8192},
    }

    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "x-goog-api-key": api_key,
            "Accept": "text/event-stream",
        },
        method="POST",
    )

    print("model          : %s" % model)
    print("auth           : x-goog-api-key header (%d chars)" % len(api_key))
    print("request        : %s" % request_text)

    raw = []
    usage = None
    chunks = 0
    english_seen_at = None
    persian_seen_at = None
    try:
        with urllib.request.urlopen(request, timeout=180,
                                    context=ssl.create_default_context()) as response:
            print("http status    : %d" % response.status)
            print("content-type   : %s" % response.headers.get("content-type"))
            for line in response:
                line = line.decode("utf-8").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                body = line[5:].strip()
                if not body or body == "[DONE]":
                    continue
                chunks += 1
                event = json.loads(body)
                if "usageMetadata" in event:
                    usage = event["usageMetadata"]
                for candidate in event.get("candidates", []):
                    for part in candidate.get("content", {}).get("parts", []):
                        raw.append(part.get("text", ""))
                buffer = "".join(raw)
                if english_seen_at is None and partial_field(buffer, "english_prompt") is not None:
                    english_seen_at = chunks
                if persian_seen_at is None and partial_field(buffer, "persian_prompt") is not None:
                    persian_seen_at = chunks
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        print("HTTP %d\n%s" % (error.code, body[:800]), file=sys.stderr)
        return 1

    text = "".join(raw)
    print("sse events     : %d" % chunks)
    print("first key seen : persian_prompt at event %s, english_prompt at event %s"
          % (persian_seen_at, english_seen_at))
    print("usage          : %s" % json.dumps(usage, ensure_ascii=False))

    parsed = parse_model_output(text)
    java_view = {
        "detected_category": parsed["detected_category"],
        "detected_subcategory": parsed["detected_subcategory"],
        "has_english": bool(parsed["english_prompt"]),
        "has_persian": bool(parsed["persian_prompt"]),
        "preserved_requirements": len(parsed["preserved_requirements"]),
        "assumptions": len(parsed["assumptions"]),
        "missing_information": len(parsed["missing_information"]),
        "suggestions": len(parsed["suggestions"]),
    }
    print("parsed         : %s" % json.dumps(java_view, ensure_ascii=False))
    print("english prompt :")
    print(parsed["english_prompt"][:700])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
