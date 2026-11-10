"""Prompt translation/compilation endpoint."""

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ...catalog import DEFAULT_MODEL, MODELS
from ...config import load_config
from ...prompt_engine import SYSTEM_PROMPT, build_user_prompt, parse_model_output
from ...services.gemini import call_gemini
from ...services.usage import record_usage


router = APIRouter()


@router.post("/translate")
async def translate(request: Request):
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid request"}, status_code=400)

    persian_text = body.get("prompt", "")
    model_id = (body.get("model", "") or "").strip()
    config = load_config()

    # Answers collected by the discovery phase. They are hard requirements from the user, so
    # they travel with the request instead of being left to the model to guess.
    answers = []
    for item in body.get("answers") or []:
        if isinstance(item, dict):
            answers.append((item.get("q", ""), item.get("a", "")))
        elif isinstance(item, str):
            answers.append(("", item))

    if not persian_text.strip():
        return JSONResponse({"error": "Prompt is empty"}, status_code=400)

    if not model_id:
        model_id = config.get("gemini_model", DEFAULT_MODEL)

    model = next((item for item in MODELS if item["id"] == model_id), None)
    if model is not None and not model.get("generate"):
        note = model.get("note", "Only text-output models support generateContent.")
        return JSONResponse(
            {"error": f"Model '{model['name']}' does not support generateContent. {note}"},
            status_code=400,
        )

    system_prompt = config.get("system_prompt_override", "") or SYSTEM_PROMPT

    try:
        text, usage = await call_gemini(
            config, system_prompt, build_user_prompt(persian_text, answers), model_id)
        record_usage(model_id, usage)
        return JSONResponse({
            "result": text,
            "parsed": parse_model_output(text),
            "usage": usage,
            "model": model_id,
        })
    except httpx.HTTPError as exc:
        return JSONResponse(
            {
                "error": (
                    f"Could not connect to Google API: {type(exc).__name__}. "
                    "Check direct access to generativelanguage.googleapis.com."
                )
            },
            status_code=500,
        )
    except Exception as exc:
        return JSONResponse({"error": str(exc)}, status_code=500)
