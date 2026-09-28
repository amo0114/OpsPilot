"""Internal decision endpoints with the Fake LLM (08 TASK-033, 05 §76-§90)."""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from opspilot_ai.config import Settings
from opspilot_ai.errors import LlmTimeoutError, LlmUnavailableError
from opspilot_ai.llm.client import LlmPrompt
from opspilot_ai.llm.fake import FakeLlmClient
from opspilot_ai.main import create_app

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "ai-runtime" / "v1"
TOKEN = "internal-test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def _fixture(name: str, kind: str, stem: str) -> dict:
    path = CONTRACT / "fixtures" / name / kind / f"{stem}.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _schema(name: str) -> Draft202012Validator:
    return Draft202012Validator(
        json.loads((CONTRACT / f"{name}.schema.json").read_text(encoding="utf-8"))
    )


STEP_REQUEST = _fixture("investigation-step-request", "valid", "full-context-all-descriptors")
DRAFT_REQUEST = _fixture("remediation-draft-request", "valid", "service-restart-allowed")


class RecordingLlm:
    """Test double: records prompts and delegates to the runtime FakeLlmClient."""

    def __init__(self, scripted: list[str] | None = None) -> None:
        self._fake = FakeLlmClient(scripted or [])
        self.prompts: list[LlmPrompt] = []

    def complete(self, prompt: LlmPrompt) -> str:
        self.prompts.append(prompt)
        return self._fake.complete(prompt)


def _client(llm: object) -> TestClient:
    return TestClient(create_app(Settings(model="fake-model", internal_token=TOKEN), llm))


@pytest.mark.parametrize(
    "path", ["/internal/v1/investigation/step", "/internal/v1/remediation/draft"]
)
@pytest.mark.parametrize(
    "headers",
    [{}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}, {"Authorization": "Basic x"}],
)
def test_decision_endpoints_require_the_internal_token(path: str, headers: dict) -> None:
    llm = RecordingLlm()
    body = STEP_REQUEST if "investigation" in path else DRAFT_REQUEST

    response = _client(llm).post(path, json=body, headers=headers)

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"
    assert TOKEN not in response.text
    assert llm.prompts == []


def test_fixed_intent_is_a_valid_step_that_echoes_java_identity() -> None:
    llm = RecordingLlm()

    response = _client(llm).post("/internal/v1/investigation/step", json=STEP_REQUEST, headers=AUTH)

    assert response.status_code == 200
    body = response.json()
    _schema("investigation-step-response").validate(body)
    assert (body["protocolVersion"], body["runNo"], body["stepId"]) == (1, 2, 42)
    diagnosis = body["completeInvestigation"]["diagnosis"]
    assert diagnosis["conclusionType"] == "UNDETERMINED"
    assert diagnosis["evidenceIds"] == []
    assert diagnosis["impactSummary"] == STEP_REQUEST["incident"]["impactSummary"]
    # The model sees exactly Java's curated context under a versioned template (07 §78)
    assert llm.prompts == [LlmPrompt("investigation-v1", STEP_REQUEST)]


def test_scripted_intent_gets_run_identity_from_the_request_not_the_model() -> None:
    answer = {
        "protocolVersion": 1,
        "runNo": 99,
        "stepId": 7,
        "intentType": "REQUEST_CAPABILITY",
        "requestCapability": {
            "capabilityKey": "queue.inspect",
            "resourceId": 13,
            "arguments": {},
            "purpose": "核对消费者组积压。",
        },
    }
    llm = RecordingLlm([json.dumps(answer)])

    body = (
        _client(llm).post("/internal/v1/investigation/step", json=STEP_REQUEST, headers=AUTH).json()
    )

    assert (body["runNo"], body["stepId"]) == (2, 42)
    assert body["requestCapability"] == answer["requestCapability"]


@pytest.mark.parametrize(
    "answer",
    [
        "I think Redis is slow.",
        "[]",
        json.dumps({"intentType": "PROPOSE_HYPOTHESIS"}),
        json.dumps(
            {
                "intentType": "PROPOSE_HYPOTHESIS",
                "proposeHypothesis": {"title": "Redis 异常"},
                "updateHypothesis": {"hypothesisId": 21, "targetStatus": "REFUTED"},
            }
        ),
        json.dumps(
            {
                "intentType": "PROPOSE_HYPOTHESIS",
                "proposeHypothesis": {"title": "x"},
                "incidentStatus": "DIAGNOSED",
            }
        ),
        json.dumps({"intentType": "PROPOSE_REMEDIATION", "proposal": {}}),
    ],
)
def test_invalid_model_output_is_502_without_echoing_it(answer: str) -> None:
    response = _client(FakeLlmClient([answer])).post(
        "/internal/v1/investigation/step", json=STEP_REQUEST, headers=AUTH
    )

    assert response.status_code == 502
    assert response.json()["code"] == "AI_OUTPUT_INVALID"
    assert "Redis" not in response.text and "DIAGNOSED" not in response.text


@pytest.mark.parametrize(
    ("failure", "status", "code"),
    [
        (LlmTimeoutError("slow"), 504, "AI_RUNTIME_TIMEOUT"),
        (LlmUnavailableError("down"), 503, "AI_RUNTIME_UNAVAILABLE"),
    ],
)
def test_model_backend_failures_map_to_status_codes(
    failure: Exception, status: int, code: str
) -> None:
    class FailingLlm:
        def complete(self, prompt: LlmPrompt) -> str:
            raise failure

    for path, body in (
        ("/internal/v1/investigation/step", STEP_REQUEST),
        ("/internal/v1/remediation/draft", DRAFT_REQUEST),
    ):
        response = _client(FailingLlm()).post(path, json=body, headers=AUTH)
        assert (response.status_code, response.json()["code"]) == (status, code)


@pytest.mark.parametrize(
    ("path", "stem", "name"),
    [
        ("/internal/v1/investigation/step", "missing-run-no", "investigation-step-request"),
        ("/internal/v1/investigation/step", "ground-truth-field", "investigation-step-request"),
        ("/internal/v1/remediation/draft", "undetermined-diagnosis", "remediation-draft-request"),
    ],
)
def test_invalid_request_is_422_and_never_reaches_the_model(
    path: str, stem: str, name: str
) -> None:
    llm = RecordingLlm()

    response = _client(llm).post(path, json=_fixture(name, "invalid", stem), headers=AUTH)

    assert response.status_code == 422
    assert response.json()["code"] == "REQUEST_VALIDATION_FAILED"
    assert llm.prompts == []


def test_fixed_remediation_proposes_the_first_allowed_action() -> None:
    llm = RecordingLlm()

    response = _client(llm).post("/internal/v1/remediation/draft", json=DRAFT_REQUEST, headers=AUTH)

    assert response.status_code == 200
    body = response.json()
    _schema("remediation-draft-response").validate(body)
    assert body["correlationId"] == DRAFT_REQUEST["correlationId"]
    assert body["proposal"]["action"]["targetResourceId"] == 12
    assert body["proposal"]["action"]["parameters"] == {}
    assert llm.prompts == [LlmPrompt("remediation-v1", DRAFT_REQUEST)]


def _proposal(target: int, **extra: object) -> str:
    action = {
        "capabilityKey": "service.restart",
        "targetResourceId": target,
        "parameters": {},
        "summary": "重新启动",
        "expectedImpactSummary": "短暂重启",
        **extra,
    }
    return json.dumps({"proposal": {"title": "恢复", "summary": "重启", "action": action}})


@pytest.mark.parametrize(
    "answer",
    [_proposal(99), _proposal(12, riskLevel="LOW"), _proposal(12, requiresApproval=False)],
)
def test_remediation_outside_allowed_actions_or_with_policy_fields_is_502(answer: str) -> None:
    response = _client(FakeLlmClient([answer])).post(
        "/internal/v1/remediation/draft", json=DRAFT_REQUEST, headers=AUTH
    )

    assert response.status_code == 502
    assert response.json()["code"] == "AI_OUTPUT_INVALID"


@pytest.mark.parametrize(
    ("env", "message"),
    [
        ({"OPSPILOT_AI_LLM_PROVIDER": "fake"}, "OPSPILOT_AI_RUNTIME_TOKEN"),
        ({"OPSPILOT_AI_RUNTIME_TOKEN": "t"}, "OPSPILOT_AI_LLM_PROVIDER"),
        ({"OPSPILOT_AI_RUNTIME_TOKEN": "t", "OPSPILOT_AI_LLM_PROVIDER": "openai"}, "unsupported"),
    ],
)
def test_settings_fail_fast_without_token_or_with_unavailable_provider(
    monkeypatch: pytest.MonkeyPatch, env: dict, message: str
) -> None:
    for key in ("OPSPILOT_AI_RUNTIME_TOKEN", "OPSPILOT_AI_LLM_PROVIDER"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("OPSPILOT_AI_MODEL", "m")
    for key, value in env.items():
        monkeypatch.setenv(key, value)

    with pytest.raises(RuntimeError, match=message):
        Settings.from_env()
