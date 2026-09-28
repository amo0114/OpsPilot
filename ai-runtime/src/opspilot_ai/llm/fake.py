"""Deterministic LLM stand-in (08 TASK-033): scripted answers first, then one fixed Intent.

The fixed answers are always protocol-legal for any valid request: investigation ends as
UNDETERMINED without claiming evidence, remediation proposes the first allowed action. It does
no reasoning and is only for control-flow work before a real model is configured.
"""

import json
from collections import deque
from collections.abc import Iterable
from typing import Any

from opspilot_ai.llm.client import LlmPrompt

INVESTIGATION_TEMPLATE = "investigation-v1"
REMEDIATION_TEMPLATE = "remediation-v1"


class FakeLlmClient:
    def __init__(self, scripted: Iterable[str] = ()) -> None:
        self._scripted = deque(scripted)

    def complete(self, prompt: LlmPrompt) -> str:
        if self._scripted:
            return self._scripted.popleft()
        if prompt.template_version == INVESTIGATION_TEMPLATE:
            return json.dumps(_undetermined(prompt.context), ensure_ascii=False)
        if prompt.template_version == REMEDIATION_TEMPLATE:
            return json.dumps(_first_allowed_action(prompt.context), ensure_ascii=False)
        raise ValueError(f"unknown prompt template: {prompt.template_version}")


def _undetermined(context: dict[str, Any]) -> dict[str, Any]:
    return {
        "intentType": "COMPLETE_INVESTIGATION",
        "completeInvestigation": {
            "diagnosis": {
                "conclusionType": "UNDETERMINED",
                "summary": "Fake LLM 未进行真实推理，暂时无法确定原因。",
                "impactSummary": context["incident"]["impactSummary"],
                "evidenceIds": [],
            }
        },
    }


def _first_allowed_action(context: dict[str, Any]) -> dict[str, Any]:
    action = context["allowedActions"][0]
    return {
        "proposal": {
            "title": f"恢复 {action['resourceName']}",
            "summary": f"Fake LLM 固定建议：重新启动 {action['resourceName']}。",
            "action": {
                "capabilityKey": action["capabilityKey"],
                "targetResourceId": action["resourceId"],
                "parameters": {},
                "summary": f"重新启动 {action['resourceName']}",
                "expectedImpactSummary": "服务将发生短暂重启。",
            },
        }
    }
