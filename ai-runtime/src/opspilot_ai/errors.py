"""Failures the internal API reports; messages never echo model output or secrets (05 §93)."""


class AiOutputInvalidError(Exception):
    """The model's answer is not a valid v1 Intent/Proposal (HTTP 502)."""


class LlmUnavailableError(Exception):
    """The model backend cannot be reached (HTTP 503)."""


class LlmTimeoutError(Exception):
    """The model backend did not answer in time (HTTP 504)."""
