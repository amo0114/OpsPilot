"""Protocol v1 typed models; the JSON Schemas in contracts/ai-runtime/v1 are the source of truth."""

from opspilot_ai.protocol.v1.investigation import (
    INVESTIGATION_STEP_RESPONSE,
    InvestigationStepRequest,
    InvestigationStepResponse,
)
from opspilot_ai.protocol.v1.remediation import RemediationDraftRequest, RemediationDraftResponse

__all__ = [
    "INVESTIGATION_STEP_RESPONSE",
    "InvestigationStepRequest",
    "InvestigationStepResponse",
    "RemediationDraftRequest",
    "RemediationDraftResponse",
]
