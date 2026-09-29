from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from opspilot_ai.api.internal import decision_response, error, require_internal_token
from opspilot_ai.errors import AiOutputInvalidError, LlmTimeoutError, LlmUnavailableError
from opspilot_ai.investigation.service import InvestigationDecisionService
from opspilot_ai.protocol.v1 import InvestigationStepRequest

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_internal_token)])


@router.post("/investigation/step")
async def investigation_step(request: Request) -> JSONResponse:
    try:
        step = InvestigationStepRequest.model_validate_json(await request.body())
    except ValidationError:
        return error(422, "REQUEST_VALIDATION_FAILED", "Request does not match protocol v1.")
    service: InvestigationDecisionService = request.app.state.investigation_service
    try:
        # the model call blocks; keep it off the event loop
        decision = await run_in_threadpool(service.decide, step)
    except AiOutputInvalidError:
        return error(502, "AI_OUTPUT_INVALID", "Model output does not match protocol v1.")
    except LlmTimeoutError:
        return error(504, "AI_RUNTIME_TIMEOUT", "Model did not answer in time.")
    except LlmUnavailableError:
        return error(503, "AI_RUNTIME_UNAVAILABLE", "Model is unavailable.")
    return decision_response(
        decision.response.to_wire(),
        request.app.state.settings,
        decision.template_version,
        decision.completion,
    )
