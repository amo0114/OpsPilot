from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from opspilot_ai.config import Settings

router = APIRouter(prefix="/internal/v1")


class HealthResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    status: Literal["UP"]
    model_provider: str = Field(serialization_alias="modelProvider")
    model: str


@router.get("/health", response_model=HealthResponse, response_model_by_alias=True)
def health(request: Request) -> HealthResponse:
    settings: Settings = request.app.state.settings
    return HealthResponse(status="UP", model_provider=settings.llm_provider, model=settings.model)
