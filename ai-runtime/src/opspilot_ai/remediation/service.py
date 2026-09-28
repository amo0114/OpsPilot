import json
from dataclasses import dataclass

from pydantic import ValidationError

from opspilot_ai.errors import AiOutputInvalidError
from opspilot_ai.llm.client import LlmClient, LlmCompletion, LlmPrompt, answer_object
from opspilot_ai.llm.fake import REMEDIATION_TEMPLATE
from opspilot_ai.protocol.v1 import RemediationDraftRequest, RemediationDraftResponse


@dataclass(frozen=True)
class RemediationDecision:
    response: RemediationDraftResponse
    template_version: str
    completion: LlmCompletion


class RemediationDraftService:
    """One remediation proposal (05 §87-§88), chosen only from Java's allowedActions.

    Risk level and approval are Java policy; the model never supplies them. Java re-validates the
    proposal against the current Diagnosis and bindings before creating anything (TASK-064).
    """

    def __init__(self, llm: LlmClient) -> None:
        self._llm = llm

    def draft(self, request: RemediationDraftRequest) -> RemediationDecision:
        completion = self._llm.complete(LlmPrompt(REMEDIATION_TEMPLATE, request.to_wire()))
        answer = answer_object(completion.text)
        answer.update(
            protocolVersion=1,
            correlationId=request.correlation_id,
            intentType="PROPOSE_REMEDIATION",
        )
        try:
            response = RemediationDraftResponse.model_validate_json(json.dumps(answer))
        except ValidationError as error:
            raise AiOutputInvalidError("model answer is not a valid remediation draft") from error
        action = response.proposal.action
        allowed = {(a.capability_key, a.resource_id) for a in request.allowed_actions}
        if (action.capability_key, action.target_resource_id) not in allowed:
            raise AiOutputInvalidError("model chose an action outside allowedActions")
        return RemediationDecision(response, REMEDIATION_TEMPLATE, completion)
