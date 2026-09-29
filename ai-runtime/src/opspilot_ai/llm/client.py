import json
import logging
from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import ValidationError

from opspilot_ai.errors import AiOutputInvalidError


@dataclass(frozen=True)
class LlmPrompt:
    """One model call: a versioned template (07 §78) and the context Java curated for this step."""

    template_version: str
    context: dict[str, Any]


@dataclass(frozen=True)
class LlmCompletion:
    """The model's raw answer and, when the backend reports it, token usage."""

    text: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class LlmClient(Protocol):
    """Returns the model's raw answer; callers validate the text against protocol v1.

    Implementations raise LlmUnavailableError or LlmTimeoutError; they never retry on their own
    (07 §86), because each attempt must stay visible to Java as its own AgentStep.
    """

    def complete(self, prompt: LlmPrompt) -> LlmCompletion: ...


log = logging.getLogger(__name__)


UNKNOWN_FIELD = "<unknown>"


def protocol_names(schema: dict[str, Any]) -> frozenset[str]:
    """Field names and discriminator tags of a protocol v1 JSON schema: the only path segments that
    may be logged. Anything else in an error path is a key the model made up."""
    names: set[str] = set()

    def walk(node: object) -> None:
        if isinstance(node, dict):
            properties = node.get("properties")
            if isinstance(properties, dict):
                names.update(properties)
            if isinstance(node.get("const"), str):
                names.add(node["const"])
            mapping = node.get("mapping")
            if isinstance(mapping, dict):
                names.update(mapping)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(schema)
    return frozenset(names)


def log_rejected(error: ValidationError, known: frozenset[str]) -> None:
    """Where an answer broke protocol v1: field paths and error types, never values (05 §93).

    Path segments that are not protocol field names or tags came from the model's JSON keys and
    are replaced by a fixed placeholder (B18-R2); list indexes are kept.
    """
    problems = [
        ".".join(
            str(part) if isinstance(part, int) or part in known else UNKNOWN_FIELD
            for part in detail["loc"]
        )
        + ":"
        + detail["type"]
        for detail in error.errors(include_input=False, include_url=False)
    ]
    log.warning("Model answer rejected by protocol v1: %s", problems[:20])


def answer_object(answer: str) -> dict[str, Any]:
    """The model must answer with one JSON object; anything else is invalid output."""
    try:
        value = json.loads(answer)
    except json.JSONDecodeError as error:
        raise AiOutputInvalidError("model answer is not JSON") from error
    if not isinstance(value, dict):
        raise AiOutputInvalidError("model answer is not a JSON object")
    return value
