import os
from dataclasses import dataclass

MODEL_PROVIDER = "openai-compatible"


@dataclass(frozen=True)
class Settings:
    model: str

    @classmethod
    def from_env(cls) -> "Settings":
        model = os.environ.get("OPSPILOT_AI_MODEL")
        if not model:
            raise RuntimeError("OPSPILOT_AI_MODEL is required")
        return cls(model=model)
