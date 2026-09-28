from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from opspilot_ai.api.internal import decision_response, error, require_internal_token
from opspilot_ai.errors import AiOutputInvalidError, LlmTimeoutError, LlmUnavailableError
from opspilot_ai.protocol.v1 import RemediationDraftRequest
from opspilot_ai.remediation.service import RemediationDraftService

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_internal_token)])


@router.post("/remediation/draft")
async def remediation_draft(request: Request) -> JSONResponse:
    try:
        draft_request = RemediationDraftRequest.model_validate_json(await request.body())
    except ValidationError:
        return error(422, "REQUEST_VALIDATION_FAILED", "Request does not match protocol v1.")
    service: RemediationDraftService = request.app.state.remediation_service
    try:
        draft = service.draft(draft_request)
    except AiOutputInvalidError:
        return error(502, "AI_OUTPUT_INVALID", "Model output does not match protocol v1.")
    except LlmTimeoutError:
        return error(504, "AI_RUNTIME_TIMEOUT", "Model did not answer in time.")
    except LlmUnavailableError:
        return error(503, "AI_RUNTIME_UNAVAILABLE", "Model is unavailable.")
    return decision_response(
        draft.response.to_wire(),
        request.app.state.settings,
        draft.template_version,
        draft.completion,
    )
