"""Google AI Studio HTTP integration."""

import json

import httpx


async def call_gemini(config, system_prompt, user_prompt, model_id,
                      temperature=0.7, max_output_tokens=8192):
    api_key = config.get("gemini_api_key", "")
    if not api_key:
        raise ValueError(
            "Google AI Studio API key is not configured. Add it from the Settings menu. "
            "(https://aistudio.google.com/apikey)"
        )

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_id}:generateContent"
    payload = {
        "system_instruction": {"parts": [{"text": system_prompt}]},
        # The caller owns the wording: the compile phase wraps the request and appends the
        # user's answers, the discovery phase sends the request on its own.
        "contents": [{
            "role": "user",
            "parts": [{"text": user_prompt}],
        }],
        "generationConfig": {"temperature": temperature, "maxOutputTokens": max_output_tokens},
    }

    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post(
            url,
            params={"key": api_key},
            headers={"Content-Type": "application/json"},
            json=payload,
        )

    if response.status_code != 200:
        content_type = response.headers.get("content-type", "").lower()
        response_text = response.text or ""
        if "html" in content_type or response_text.lstrip().lower().startswith("<!doctype"):
            raise ValueError(
                "Google API rejected the request (HTTP 403). Check access to "
                "generativelanguage.googleapis.com from this network."
            )
        try:
            error_data = response.json()
            error = error_data["error"]
            code = error.get("code", response.status_code)
            message = error.get("message", str(error))
        except (json.JSONDecodeError, KeyError, TypeError):
            code = response.status_code
            message = response_text.strip()[:400] or "No valid JSON response was received from the API."
        if "api key" in message.lower() and "not valid" in message.lower():
            message = "The API key is invalid. Create a new key at aistudio.google.com/apikey."
        raise ValueError(f"[{code}] {message}")

    try:
        data = response.json()
    except json.JSONDecodeError as exc:
        raise ValueError(
            f"No valid API response was received (status={response.status_code}): "
            f"{response.text.strip()[:400] if response.text else 'empty response'}"
        ) from exc

    if "error" in data:
        error = data["error"]
        code = error.get("code", response.status_code)
        message = error.get("message", str(error))
        raise ValueError(f"[{code}] {message}")

    try:
        text = data["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError(
            "No model response was received: " + json.dumps(data, ensure_ascii=False)[:600]
        ) from exc

    return text, data.get("usageMetadata", {})
