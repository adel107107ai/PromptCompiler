"""Interview analysis endpoint (gام اول): the critical-ambiguity gate and questions."""

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from typing import Optional

from ...catalog import DEFAULT_MODEL, MODELS
from ...config import load_config
from ...prompt_engine import (
    ANALYSIS_LIGHT_MODEL,
    ANALYSIS_MAX_OUTPUT_TOKENS,
    ANALYSIS_TEMPERATURE,
    INTERVIEW_ANALYSIS_PROMPT,
    parse_clarify_output,
)
from ...services.gemini import call_gemini
from ...services.usage import record_usage


router = APIRouter()


class AnalyzeRequest(BaseModel):
    prompt: str = Field(..., min_length=1)
    model: Optional[str] = None


def analysis_model_for(requested: str) -> str:
    """The analysis pass runs on the light model so the gate is fast and cheap."""
    entry = next((item for item in MODELS if item["id"] == ANALYSIS_LIGHT_MODEL), None)
    if entry is not None and entry.get("generate"):
        return ANALYSIS_LIGHT_MODEL
    return requested


@router.post("/analyze")
async def analyze(request: AnalyzeRequest):
    config = load_config()
    api_key = config.get("gemini_api_key", "")
    if not api_key:
        return JSONResponse(
            {"error": "Google AI Studio API key is not configured. Add it from the Settings menu."},
            status_code=400,
        )

    requested = (request.model or "").strip() or config.get("gemini_model", DEFAULT_MODEL)
    model = analysis_model_for(requested)

    try:
        text, usage = await call_gemini(
            config,
            INTERVIEW_ANALYSIS_PROMPT,
            request.prompt,
            model,
            temperature=ANALYSIS_TEMPERATURE,
            max_output_tokens=ANALYSIS_MAX_OUTPUT_TOKENS,
        )
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

    record_usage(model, usage)
    return JSONResponse({
        "analysis": parse_clarify_output(text),
        "usage": usage,
        "model": model,
    })


@router.post("/clarify")
async def clarify_legacy(request: Request):
    """Backward-compatible alias of /analyze for anything that shipped against it."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid request"}, status_code=400)
    return await analyze(AnalyzeRequest(prompt=body.get("prompt", ""), model=body.get("model")))
