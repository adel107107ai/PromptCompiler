#!/usr/bin/env python3
"""Exercise the discovery prompt against the live API.

Shows, for a few representative Persian requests, whether the phase asks the right
questions (and stays quiet when the request is already clear).

Usage: python3 tools/smoke_clarify.py ["persian request"]...
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
from prompt_compiler.prompt_engine import (  # noqa: E402
    INTERVIEW_ANALYSIS_PROMPT,
    parse_clarify_output,
)

SAMPLES = [
    "یه وب‌سایت فروشگاهی بساز که قیمت محصولات رو نشون بده",
    "این متن رو رسمی‌تر کن: سلام، فردا جلسه داریم و باید گزارش بدی.",
    "یه اسکریپت پایتون بنویس که فایل‌های یک پوشه رو بر اساس پسوند دسته‌بندی کنه",
    "برای تیمم یه راهنمای آنبوردینگ بنویس",
]


def ask(api_key, model, request_text):
    payload = {
        "system_instruction": {"parts": [{"text": INTERVIEW_ANALYSIS_PROMPT}]},
        "contents": [{"role": "user", "parts": [{"text": request_text}]}],
        "generationConfig": {"temperature": 0.4, "maxOutputTokens": 2048},
    }
    url = ("https://generativelanguage.googleapis.com/v1beta/models/"
           "%s:generateContent" % model)
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "x-goog-api-key": api_key,
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120,
                                context=ssl.create_default_context()) as response:
        data = json.loads(response.read().decode("utf-8"))
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    return text, data.get("usageMetadata", {})


def main(argv):
    config = load_config()
    api_key = config.get("gemini_api_key", "").strip()
    if not api_key:
        print("smoke_clarify: config.json has no gemini_api_key", file=sys.stderr)
        return 2
    model = config.get("gemini_model", "gemini-3.5-flash")
    samples = argv or SAMPLES

    total = 0
    for request_text in samples:
        print("=" * 78)
        print("REQUEST : %s" % request_text)
        try:
            raw, usage = ask(api_key, model, request_text)
        except urllib.error.HTTPError as error:
            print("HTTP %d: %s" % (error.code, error.read().decode("utf-8", "replace")[:400]))
            return 1
        parsed = parse_clarify_output(raw)
        print("READY   : %s%s" % (parsed["ready"], "  (degraded)" if parsed["degraded"] else ""))
        print("UNDERST.: %s" % parsed["understanding"])
        for item in parsed["ambiguities"]:
            print("  ambiguity: %s" % item)
        for index, question in enumerate(parsed["questions"], 1):
            print("  Q%d [%s] %s" % (index, question["id"], question["question"]))
            if question["why"]:
                print("      why: %s" % question["why"])
            if question["options"]:
                print("      options: %s" % " | ".join(question["options"]))
        total += usage.get("totalTokenCount", 0)
    print("=" * 78)
    print("total tokens across %d requests: %d" % (len(samples), total))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
