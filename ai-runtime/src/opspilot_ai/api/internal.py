"""Shared pieces of the internal /internal/v1 API: token check and error bodies (05 §90, §93)."""

import hmac

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse

from opspilot_ai.config import Settings


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
