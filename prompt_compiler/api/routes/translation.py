"""Prompt translation/compilation endpoints, including the streaming interview compile."""

import json

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from typing import List, Optional

from ...catalog import DEFAULT_MODEL, MODELS
from ...config import load_config
from ...prompt_engine import (
    REQUEST_PREFIX,
    SYSTEM_PROMPT,
    build_answers_block,
    build_user_prompt,
    parse_model_output,
)
from ...services.gemini import call_gemini, stream_gemini
from ...services.usage import record_usage


router = APIRouter()


class ClarificationItem(BaseModel):
    question_id: str = ""
    question_fa: str = ""
    user_answer: str = ""


class CompileWithInterviewRequest(BaseModel):
    original_prompt: str = Field(..., min_length=1)
    clarifications: List[ClarificationItem] = []
    model: Optional[str] = None


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


@router.post("/compile-stream")
async def compile_stream(request: CompileWithInterviewRequest):
    """Second interview step: compile with the answers injected, streamed as SSE."""
    config = load_config()
    api_key = config.get("gemini_api_key", "")
    if not api_key:
        return JSONResponse(
            {"error": "Google AI Studio API key is not configured. Add it from the Settings menu."},
            status_code=400,
        )

    model_id = (request.model or "").strip() or config.get("gemini_model", DEFAULT_MODEL)
    model = next((item for item in MODELS if item["id"] == model_id), None)
    if model is not None and not model.get("generate"):
        note = model.get("note", "Only text-output models support generateContent.")
        return JSONResponse(
            {"error": f"Model '{model['name']}' does not support generateContent. {note}"},
            status_code=400,
        )

    # Interview answers become the CLARIFIED_SPECIFICATIONS block: explicit user
    # requirements that still cannot override the request's own prohibitions.
    answers = [(item.question_fa or item.question_id, item.user_answer)
               for item in request.clarifications if item.user_answer.strip()]
    user_prompt = build_user_prompt(request.original_prompt, answers) if answers else (
        REQUEST_PREFIX + request.original_prompt)
    system_prompt = config.get("system_prompt_override", "") or SYSTEM_PROMPT

    async def event_stream():
        usage = {}
        try:
            async for chunk in stream_gemini(config, system_prompt, user_prompt, model_id):
                try:
                    event = json.loads(chunk)
                    if isinstance(event.get("usageMetadata"), dict):
                        usage.update(event["usageMetadata"])
                except json.JSONDecodeError:
                    pass
                yield "data: %s\n\n" % chunk
        except ValueError as failure:
            yield "data: %s\n\n" % json.dumps({"error": str(failure)}, ensure_ascii=False)
            return
        if usage:
            record_usage(model_id, usage)
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
