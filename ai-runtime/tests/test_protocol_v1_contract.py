"""Python side of the protocol v1 contract test (08 TASK-031/032, 07 §84, §110).

Reads the same contracts/ai-runtime/v1 fixtures as the Java AiProtocolContractTest:
valid fixtures must pass the JSON Schema and Pydantic and serialize back to equivalent JSON;
invalid ones must fail both; invalid-model-only ones break a rule JSON Schema cannot express
(cross-field equality, calendar validity), so the schema accepts them while both typed models
must reject them.
"""

import ast
import json
import tomllib
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from opspilot_ai.protocol.v1 import (
    INVESTIGATION_STEP_RESPONSE,
    InvestigationStepRequest,
    RemediationDraftRequest,
    RemediationDraftResponse,
)
from opspilot_ai.protocol.v1.capability import KEYWORD_PATTERN
from opspilot_ai.protocol.v1.common import NON_BLANK_PATTERN

RUNTIME_ROOT = Path(__file__).resolve().parents[1]
CONTRACT = RUNTIME_ROOT.parent / "contracts" / "ai-runtime" / "v1"


def _model(model: Any) -> Callable[[str], Any]:
    return model.model_validate_json


PARSERS: dict[str, Callable[[str], Any]] = {
    "investigation-step-request": _model(InvestigationStepRequest),
    "investigation-step-response": INVESTIGATION_STEP_RESPONSE.validate_json,
    "remediation-draft-request": _model(RemediationDraftRequest),
    "remediation-draft-response": _model(RemediationDraftResponse),
}


def _validator(name: str) -> Draft202012Validator:
    schema = json.loads((CONTRACT / f"{name}.schema.json").read_text(encoding="utf-8"))
    return Draft202012Validator(schema)


def _fixtures(kind: str) -> list[Any]:
    return [
        pytest.param(name, path, id=f"{name}/{kind}/{path.stem}")
        for name in sorted(PARSERS)
        for path in sorted((CONTRACT / "fixtures" / name / kind).glob("*.json"))
    ]


def _without_nulls(value: Any) -> Any:
    """Optional fields: absent and null mean the same; serialization omits them."""
    if isinstance(value, dict):
        return {k: _without_nulls(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [_without_nulls(v) for v in value]
    return value


@pytest.mark.parametrize("name", sorted(PARSERS))
def test_schema_is_valid_draft_2020_12_with_fixtures_on_both_sides(name: str) -> None:
    schema = json.loads((CONTRACT / f"{name}.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    assert list((CONTRACT / "fixtures" / name / "valid").glob("*.json"))
    assert list((CONTRACT / "fixtures" / name / "invalid").glob("*.json"))


def test_text_patterns_match_schema() -> None:
    """Non-blank and keyword rules use one explicit White_Space set on every side."""
    for name in PARSERS:
        schema = (CONTRACT / f"{name}.schema.json").read_text(encoding="utf-8")
        patterns = {
            node["pattern"]
            for node in _nodes(json.loads(schema))
            if isinstance(node.get("pattern"), str) and node["pattern"].startswith("[^")
        }
        assert patterns <= {NON_BLANK_PATTERN}, name
    response = json.loads(
        (CONTRACT / "investigation-step-response.schema.json").read_text(encoding="utf-8")
    )
    assert response["$defs"]["keyword"]["pattern"] == KEYWORD_PATTERN


def _nodes(value: Any) -> list[dict]:
    if isinstance(value, dict):
        return [value, *(n for v in value.values() for n in _nodes(v))]
    if isinstance(value, list):
        return [n for v in value for n in _nodes(v)]
    return []


@pytest.mark.parametrize(("name", "path"), _fixtures("valid"))
def test_valid_fixture_passes_schema_and_round_trips(name: str, path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    document = json.loads(text)
    _validator(name).validate(document)

    model = PARSERS[name](text)

    assert model.to_wire() == _without_nulls(document)


@pytest.mark.parametrize(("name", "path"), _fixtures("invalid"))
def test_invalid_fixture_is_rejected_by_schema_and_model(name: str, path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    assert not _validator(name).is_valid(json.loads(text))
    with pytest.raises(ValidationError):
        PARSERS[name](text)


@pytest.mark.parametrize(("name", "path"), _fixtures("invalid-model-only"))
def test_model_only_violation_is_rejected_by_model(name: str, path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    # JSON Schema 2020-12 cannot compare two fields or check a calendar; the typed models do
    assert _validator(name).is_valid(json.loads(text))
    with pytest.raises(ValidationError):
        PARSERS[name](text)


FORBIDDEN_MODULES = {
    "redis",
    "pymysql",
    "mysql",
    "MySQLdb",
    "aiomysql",
    "asyncmy",
    "sqlalchemy",
    "docker",
    "aiodocker",
}


def test_runtime_has_no_business_infrastructure_clients() -> None:
    """Python holds no Redis/MySQL/Docker client (08 TASK-031, BND-005)."""
    for source in (RUNTIME_ROOT / "src").rglob("*.py"):
        tree = ast.parse(source.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                roots = {alias.name.split(".")[0] for alias in node.names}
            elif isinstance(node, ast.ImportFrom) and node.module:
                roots = {node.module.split(".")[0]}
            else:
                continue
            assert not roots & FORBIDDEN_MODULES, f"{source}: {roots & FORBIDDEN_MODULES}"

    project = tomllib.loads((RUNTIME_ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    declared = [*project["project"]["dependencies"], *project["dependency-groups"]["dev"]]
    names = {entry.split("==")[0].split("[")[0].lower() for entry in declared}
    assert not names & {m.lower() for m in FORBIDDEN_MODULES}
