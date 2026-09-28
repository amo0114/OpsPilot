"""Shared pieces of the internal /internal/v1 API: token check and error bodies (05 §90, §93)."""

import hmac

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse

from opspilot_ai.config import Settings
from opspilot_ai.llm.client import LlmCompletion


def require_internal_token(request: Request) -> None:
    """Java sends `Authorization: Bearer <token>`; compared in constant time, never logged."""
    settings: Settings = request.app.state.settings
    header = request.headers.get("authorization", "")
    scheme, _, presented = header.partition(" ")
    expected = settings.internal_token
    if (
        not expected
        or scheme.lower() != "bearer"
        or not hmac.compare_digest(presented.encode(), expected.encode())
    ):
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")


def error(status: int, code: str, message: str) -> JSONResponse:
    """Fixed messages only; request bodies and model output are never echoed."""
    return JSONResponse(status_code=status, content={"code": code, "message": message})


def decision_response(
    content: dict, settings: Settings, template_version: str, completion: LlmCompletion
) -> JSONResponse:
    """Call metadata travels in headers so the v1 body contract stays unchanged (04 §59, TASK-038).

    Token headers are sent only when the model backend reported usage.
    """
    headers = {
        "X-OpsPilot-Model-Provider": settings.llm_provider,
        "X-OpsPilot-Model-Name": settings.model,
        "X-OpsPilot-Prompt-Template-Version": template_version,
    }
    if completion.prompt_tokens is not None:
        headers["X-OpsPilot-Prompt-Tokens"] = str(completion.prompt_tokens)
    if completion.completion_tokens is not None:
        headers["X-OpsPilot-Completion-Tokens"] = str(completion.completion_tokens)
    return JSONResponse(content=content, headers=headers)
