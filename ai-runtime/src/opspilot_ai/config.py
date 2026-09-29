import os
from dataclasses import dataclass, field

FAKE_PROVIDER = "fake"
OPENAI_COMPATIBLE_PROVIDER = "openai-compatible"
SUPPORTED_PROVIDERS = frozenset({FAKE_PROVIDER, OPENAI_COMPATIBLE_PROVIDER})
DEFAULT_LLM_TIMEOUT_SECONDS = 50.0
MAX_LLM_TIMEOUT_SECONDS = 600.0


@dataclass(frozen=True)
class Settings:
    """Runtime settings from the environment; the internal token and the LLM API key are secrets
    and never logged.

    `fake` is the deterministic stand-in for tests (07 §112); `openai-compatible` calls a real
    model and needs its base URL and API key. Misconfiguration fails fast at startup.
    """

    model: str
    internal_token: str = ""
    llm_provider: str = FAKE_PROVIDER
    llm_base_url: str = ""
    llm_api_key: str = field(default="", repr=False)
    llm_timeout_seconds: float = DEFAULT_LLM_TIMEOUT_SECONDS
    llm_json_mode: bool = True

    def __post_init__(self) -> None:
        if self.llm_provider not in SUPPORTED_PROVIDERS:
            raise RuntimeError(f"unsupported LLM provider: {self.llm_provider}")
        if self.llm_provider == OPENAI_COMPATIBLE_PROVIDER:
            if not self.llm_base_url:
                raise RuntimeError("OPSPILOT_AI_LLM_BASE_URL is required")
            if not self.llm_api_key:
                raise RuntimeError("OPSPILOT_AI_LLM_API_KEY is required")
        if not 0 < self.llm_timeout_seconds <= MAX_LLM_TIMEOUT_SECONDS:
            raise RuntimeError("OPSPILOT_AI_LLM_TIMEOUT_SECONDS must be in (0, 600]")

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
        timeout = os.environ.get("OPSPILOT_AI_LLM_TIMEOUT_SECONDS")
        try:
            timeout_seconds = float(timeout) if timeout else DEFAULT_LLM_TIMEOUT_SECONDS
        except ValueError:
            raise RuntimeError("OPSPILOT_AI_LLM_TIMEOUT_SECONDS must be a number") from None
        json_mode = os.environ.get("OPSPILOT_AI_LLM_JSON_MODE", "true").lower()
        if json_mode not in ("true", "false"):
            raise RuntimeError("OPSPILOT_AI_LLM_JSON_MODE must be true or false")
        return cls(
            model=model,
            internal_token=token,
            llm_provider=provider,
            llm_base_url=os.environ.get("OPSPILOT_AI_LLM_BASE_URL", ""),
            llm_api_key=os.environ.get("OPSPILOT_AI_LLM_API_KEY", ""),
            llm_timeout_seconds=timeout_seconds,
            llm_json_mode=json_mode == "true",
        )
