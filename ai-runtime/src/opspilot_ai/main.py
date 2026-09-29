from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from opspilot_ai.api import health, investigation, remediation
from opspilot_ai.api.internal import error
from opspilot_ai.config import OPENAI_COMPATIBLE_PROVIDER, Settings
from opspilot_ai.investigation.service import InvestigationDecisionService
from opspilot_ai.llm.client import LlmClient
from opspilot_ai.llm.fake import FakeLlmClient
from opspilot_ai.llm.openai_compatible import OpenAiCompatibleClient
from opspilot_ai.remediation.service import RemediationDraftService


def create_app(settings: Settings, llm: LlmClient | None = None) -> FastAPI:
    app = FastAPI(title="OpsPilot AI Runtime", version="0.1.0")
    app.state.settings = settings
    model = llm if llm is not None else _llm(settings)
    app.state.investigation_service = InvestigationDecisionService(model)
    app.state.remediation_service = RemediationDraftService(model)
    app.include_router(health.router)
    app.include_router(investigation.router)
    app.include_router(remediation.router)

    @app.exception_handler(HTTPException)
    async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
        if exc.status_code == 401:
            return error(401, "UNAUTHORIZED", "Internal token missing or invalid.")
        return error(exc.status_code, "REQUEST_REJECTED", "Request rejected.")

    return app


def _llm(settings: Settings) -> LlmClient:
    if settings.llm_provider == OPENAI_COMPATIBLE_PROVIDER:
        return OpenAiCompatibleClient(
            settings.llm_base_url,
            settings.llm_api_key,
            settings.model,
            settings.llm_timeout_seconds,
            settings.llm_json_mode,
        )
    return FakeLlmClient()


def app_from_env() -> FastAPI:
    return create_app(Settings.from_env())
