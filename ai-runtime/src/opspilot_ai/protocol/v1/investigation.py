"""POST /internal/v1/investigation/step request and response (05 §77-§86).

Java owns investigationId, runNo and stepId; the response only echoes them and carries exactly one
primary Intent. Whether runNo is current or ids exist is decided by Java, not here.
"""

from typing import Annotated, Literal

from pydantic import Field, TypeAdapter, model_validator

from opspilot_ai.protocol.v1.capability import CapabilityDescriptor, RequestCapability
from opspilot_ai.protocol.v1.common import (
    ConclusionType,
    CorrelationId,
    Count,
    EvidenceRelation,
    HypothesisStatus,
    Id,
    IncidentKey,
    ObservationKind,
    ProtocolModel,
    ProtocolVersion,
    ResourceKey,
    ResourceType,
    RunNo,
    TargetStatus,
    Text200,
    Text1000,
    Text2000,
    Timestamp,
    Unique,
)

MAX_DIAGNOSIS_EVIDENCE = 50


class IncidentContext(ProtocolModel):
    incident_key: IncidentKey
    title: Text200
    impact_summary: Text1000
    started_at: Timestamp


class AffectedResourceContext(ProtocolModel):
    resource_id: Id
    resource_key: ResourceKey
    resource_type: ResourceType


class HypothesisContext(ProtocolModel):
    id: Id
    title: Text200
    description: Text2000 | None = None
    status: HypothesisStatus


class ObservationContext(ProtocolModel):
    id: Id
    run_no: RunNo
    resource_key: ResourceKey
    kind: ObservationKind
    summary: Text1000
    observed_at: Timestamp


class EvidenceContext(ProtocolModel):
    id: Id
    observation_id: Id
    hypothesis_id: Id
    relation: EvidenceRelation
    reason: Text1000


class CurrentDiagnosisContext(ProtocolModel):
    version: RunNo
    run_no: RunNo
    conclusion_type: ConclusionType
    primary_hypothesis_id: Id | None = None
    summary: Text2000
    evidence_ids: Annotated[list[Id], Unique]


class BudgetContext(ProtocolModel):
    scope: Literal["ACTIVE_RUN"]
    capability_calls_used: Count
    capability_calls_limit: Count
    remaining_capability_calls: Count
    elapsed_seconds: Count
    duration_limit_seconds: Count


class TimelineContext(ProtocolModel):
    event_type: Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
    occurred_at: Timestamp
    summary: Text1000


class InvestigationStepRequest(ProtocolModel):
    protocol_version: ProtocolVersion
    investigation_id: Id
    run_no: RunNo
    step_id: Id
    correlation_id: CorrelationId
    incident: IncidentContext
    affected_resources: list[AffectedResourceContext]
    hypotheses: list[HypothesisContext]
    observations: list[ObservationContext]
    evidence: list[EvidenceContext]
    current_diagnosis: CurrentDiagnosisContext | None = None
    available_capabilities: list[CapabilityDescriptor]
    budget: BudgetContext
    recent_timeline: list[TimelineContext]


class ProposeHypothesis(ProtocolModel):
    title: Text200
    description: Text2000 | None = None


class UpdateHypothesis(ProtocolModel):
    hypothesis_id: Id
    target_status: TargetStatus
    reason: Text1000 | None = None


class ProposeEvidenceLink(ProtocolModel):
    observation_id: Id
    hypothesis_id: Id
    relation: EvidenceRelation
    reason: Text1000


class HypothesisUpdate(ProtocolModel):
    hypothesis_id: Id
    target_status: TargetStatus


class DiagnosisDraftV1(ProtocolModel):
    conclusion_type: ConclusionType
    primary_hypothesis_id: Id | None = None
    summary: Text2000
    impact_summary: Text1000
    evidence_ids: Annotated[list[Id], Field(max_length=MAX_DIAGNOSIS_EVIDENCE), Unique]

    @model_validator(mode="after")
    def _primary_hypothesis_required(self) -> "DiagnosisDraftV1":
        if self.conclusion_type != "UNDETERMINED" and self.primary_hypothesis_id is None:
            raise ValueError("PRIMARY/POSSIBLE require primaryHypothesisId")
        return self


class CompleteInvestigation(ProtocolModel):
    diagnosis: DiagnosisDraftV1


class _Step(ProtocolModel):
    protocol_version: ProtocolVersion
    run_no: RunNo
    step_id: Id


class RequestCapabilityStep(_Step):
    intent_type: Literal["REQUEST_CAPABILITY"]
    request_capability: RequestCapability


class ProposeHypothesisStep(_Step):
    intent_type: Literal["PROPOSE_HYPOTHESIS"]
    propose_hypothesis: ProposeHypothesis


class UpdateHypothesisStep(_Step):
    intent_type: Literal["UPDATE_HYPOTHESIS"]
    update_hypothesis: UpdateHypothesis


class ProposeEvidenceLinkStep(_Step):
    """The only Intent that may carry a status update, and only for the same Hypothesis (05 §82)."""

    intent_type: Literal["PROPOSE_EVIDENCE_LINK"]
    propose_evidence_link: ProposeEvidenceLink
    hypothesis_update: HypothesisUpdate | None = None

    @model_validator(mode="after")
    def _same_hypothesis(self) -> "ProposeEvidenceLinkStep":
        update = self.hypothesis_update
        if update is not None and update.hypothesis_id != self.propose_evidence_link.hypothesis_id:
            raise ValueError("hypothesisUpdate must target the linked hypothesis")
        return self


class CompleteInvestigationStep(_Step):
    intent_type: Literal["COMPLETE_INVESTIGATION"]
    complete_investigation: CompleteInvestigation


InvestigationStepResponse = Annotated[
    RequestCapabilityStep
    | ProposeHypothesisStep
    | UpdateHypothesisStep
    | ProposeEvidenceLinkStep
    | CompleteInvestigationStep,
    Field(discriminator="intent_type"),
]

INVESTIGATION_STEP_RESPONSE: TypeAdapter[InvestigationStepResponse] = TypeAdapter(
    InvestigationStepResponse
)
