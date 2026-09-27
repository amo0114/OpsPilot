"""POST /internal/v1/remediation/draft request and response (05 §87-§88).

The AI proposes only from allowedActions; riskLevel, requiresApproval, RecoveryPolicy and
container/execution context are not part of its output.
"""

from typing import Literal

from pydantic import Field

from opspilot_ai.protocol.v1.common import (
    CorrelationId,
    Id,
    IncidentKey,
    ProtocolModel,
    ProtocolVersion,
    ResourceKey,
    RunNo,
    Text128,
    Text200,
    Text500,
    Text1000,
    Text2000,
)


class RemediationIncident(ProtocolModel):
    incident_key: IncidentKey
    impact_summary: Text1000


class RemediationEvidence(ProtocolModel):
    id: Id
    summary: Text1000


class RemediationDiagnosis(ProtocolModel):
    version: RunNo
    conclusion_type: Literal["PRIMARY_CAUSE_IDENTIFIED", "POSSIBLE_CAUSE"]
    summary: Text2000
    evidence: list[RemediationEvidence] = Field(min_length=1)


class AllowedAction(ProtocolModel):
    capability_key: Literal["service.restart"]
    resource_id: Id
    resource_key: ResourceKey
    resource_name: Text128


class RemediationDraftRequest(ProtocolModel):
    protocol_version: ProtocolVersion
    correlation_id: CorrelationId
    incident: RemediationIncident
    diagnosis: RemediationDiagnosis
    allowed_actions: list[AllowedAction] = Field(min_length=1)


class ServiceRestartParametersV1(ProtocolModel):
    """service.restart accepts no AI parameters (06 §105); the wire value is an explicit {}."""


class RemediationAction(ProtocolModel):
    capability_key: Literal["service.restart"]
    target_resource_id: Id
    parameters: ServiceRestartParametersV1
    summary: Text500
    expected_impact_summary: Text1000


class RemediationProposal(ProtocolModel):
    title: Text200
    summary: Text2000
    action: RemediationAction


class RemediationDraftResponse(ProtocolModel):
    protocol_version: ProtocolVersion
    correlation_id: CorrelationId
    intent_type: Literal["PROPOSE_REMEDIATION"]
    proposal: RemediationProposal
