import json
from dataclasses import dataclass
from typing import Any, Protocol

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


def answer_object(answer: str) -> dict[str, Any]:
    """The model must answer with one JSON object; anything else is invalid output."""
    try:
        value = json.loads(answer)
    except json.JSONDecodeError as error:
        raise AiOutputInvalidError("model answer is not JSON") from error
    if not isinstance(value, dict):
        raise AiOutputInvalidError("model answer is not a JSON object")
    return value
