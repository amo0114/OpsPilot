import json
from dataclasses import dataclass

from pydantic import ValidationError

from opspilot_ai.errors import AiOutputInvalidError
from opspilot_ai.llm.client import (
    LlmClient,
    LlmCompletion,
    LlmPrompt,
    answer_object,
    log_rejected,
    protocol_names,
)
from opspilot_ai.llm.fake import INVESTIGATION_TEMPLATE
from opspilot_ai.protocol.v1 import (
    INVESTIGATION_STEP_RESPONSE,
    InvestigationStepRequest,
    InvestigationStepResponse,
)

_PROTOCOL_NAMES = protocol_names(INVESTIGATION_STEP_RESPONSE.json_schema())


@dataclass(frozen=True)
class InvestigationDecision:
    """The validated step plus call metadata Java records on the AgentStep (04 §59)."""

    response: InvestigationStepResponse
    template_version: str
    completion: LlmCompletion


class InvestigationDecisionService:
    """One investigation step (05 §76-§86): ask the model for exactly one Intent.

    The model only chooses the Intent and its payload. protocolVersion, runNo and stepId are copied
    from Java's request, so the model can never pick a run; Java still checks the echo (BND-015).
    """

    def __init__(self, llm: LlmClient) -> None:
        self._llm = llm

    def decide(self, request: InvestigationStepRequest) -> InvestigationDecision:
        completion = self._llm.complete(LlmPrompt(INVESTIGATION_TEMPLATE, request.to_wire()))
        intent = answer_object(completion.text)
        intent.update(protocolVersion=1, runNo=request.run_no, stepId=request.step_id)
        try:
            response = INVESTIGATION_STEP_RESPONSE.validate_json(json.dumps(intent))
        except ValidationError as error:
            log_rejected(error, _PROTOCOL_NAMES)
            raise AiOutputInvalidError("model answer is not a valid investigation step") from error
        return InvestigationDecision(response, INVESTIGATION_TEMPLATE, completion)
