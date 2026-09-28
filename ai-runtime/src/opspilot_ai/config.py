import os
from dataclasses import dataclass

FAKE_PROVIDER = "fake"
SUPPORTED_PROVIDERS = frozenset({FAKE_PROVIDER})


@dataclass(frozen=True)
class Settings:
    """Runtime settings from the environment; the internal token is a secret and never logged.

    Only the Fake LLM exists so far; the OpenAI-compatible client is enabled by configuration in
    a later task (08 TASK-033), so any other provider fails fast at startup.
    """

    model: str
    internal_token: str = ""
    llm_provider: str = FAKE_PROVIDER

    def __post_init__(self) -> None:
        if self.llm_provider not in SUPPORTED_PROVIDERS:
            raise RuntimeError(f"unsupported LLM provider: {self.llm_provider}")

    @classmethod
    def from_env(cls) -> "Settings":
        model = os.environ.get("OPSPILOT_AI_MODEL")
        if not model:
            raise RuntimeError("OPSPILOT_AI_MODEL is required")
        token = os.environ.get("OPSPILOT_AI_RUNTIME_TOKEN")
        if not token:
            raise RuntimeError("OPSPILOT_AI_RUNTIME_TOKEN is required")
        provider = os.environ.get("OPSPILOT_AI_LLM_PROVIDER")
        if not provider:
            raise RuntimeError("OPSPILOT_AI_LLM_PROVIDER is required")
        return cls(model=model, internal_token=token, llm_provider=provider)
