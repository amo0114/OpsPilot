"""Shared field types of protocol v1, aligned with contracts/ai-runtime/v1/*.schema.json.

Optional fields may be absent or null with the same meaning; serialization omits them.
"""

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    StringConstraints,
)
from pydantic.alias_generators import to_camel


class ProtocolModel(BaseModel):
    """Strict wire model: camelCase names, no unknown fields, no scalar coercion."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_alias=True,
        validate_by_name=False,
        serialize_by_alias=True,
        extra="forbid",
        strict=True,
        frozen=True,
    )

    def to_wire(self) -> dict:
        return self.model_dump(mode="json", by_alias=True, exclude_none=True)


def _unique[T: Sequence](values: T) -> T:
    if len(set(values)) != len(values):
        raise ValueError("items must be unique")
    return values


Unique = AfterValidator(_unique)
"""JSON Schema uniqueItems for list fields."""


def _exact_int(value: object) -> object:
    """Integer constants must be JSON integers: Literal alone would accept true or 1.0."""
    if type(value) is not int:
        raise ValueError("expected an integer")
    return value


ExactInt = BeforeValidator(_exact_int)

# Unicode White_Space, spelled out so every engine (JSON Schema validators, Pydantic's regex,
# Java) agrees; engine-specific \s or isBlank() differ on U+001C-001F, U+0085, U+00A0.
WHITE_SPACE_RANGES = (
    (0x09, 0x0D),
    (0x20, 0x20),
    (0x85, 0x85),
    (0xA0, 0xA0),
    (0x1680, 0x1680),
    (0x2000, 0x200A),
    (0x2028, 0x2029),
    (0x202F, 0x202F),
    (0x205F, 0x205F),
    (0x3000, 0x3000),
)


def char_class(ranges: tuple[tuple[int, int], ...]) -> str:
    """Regex character-class body using \\uXXXX escapes."""

    def escape(code_point: int) -> str:
        return "\\u" + format(code_point, "04X")

    return "".join(
        escape(low) if low == high else f"{escape(low)}-{escape(high)}" for low, high in ranges
    )


NON_BLANK_PATTERN = "[^" + char_class(WHITE_SPACE_RANGES) + "]"


ProtocolVersion = Annotated[Literal[1], ExactInt]
Id = Annotated[int, Field(ge=1, le=9223372036854775807)]
RunNo = Annotated[int, Field(ge=1, le=2147483647)]
Count = Annotated[int, Field(ge=0, le=2147483647)]
CorrelationId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")]
IncidentKey = Annotated[str, StringConstraints(pattern=r"^INC-[0-9]{8}-[0-9]{4,}$")]
ResourceKey = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9-]{0,63}$")]


def _calendar_timestamp(value: str) -> str:
    """The pattern fixes the shape; the date and time must also exist on the calendar."""
    datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ")
    return value


Timestamp = Annotated[
    str,
    StringConstraints(
        pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$"
    ),
    AfterValidator(_calendar_timestamp),
]


def _text(max_length: int) -> StringConstraints:
    """Non-blank text, at most max_length code points."""
    return StringConstraints(min_length=1, max_length=max_length, pattern=NON_BLANK_PATTERN)


Text128 = Annotated[str, _text(128)]
Text200 = Annotated[str, _text(200)]
Text500 = Annotated[str, _text(500)]
Text1000 = Annotated[str, _text(1000)]
Text2000 = Annotated[str, _text(2000)]

ResourceType = Literal[
    "SERVICE", "DATABASE", "CACHE", "MESSAGE_QUEUE", "CONSUMER", "EXTERNAL_DEPENDENCY"
]
HypothesisStatus = Literal["PENDING", "SUPPORTED", "INSUFFICIENT_EVIDENCE", "REFUTED"]
TargetStatus = Literal["SUPPORTED", "INSUFFICIENT_EVIDENCE", "REFUTED"]
ObservationKind = Literal[
    "METRIC",
    "LOG_PATTERN",
    "SERVICE_STATUS",
    "DATABASE_STATUS",
    "CACHE_STATUS",
    "QUEUE_STATUS",
    "OTHER",
]
EvidenceRelation = Literal["SUPPORTS", "REFUTES", "CONTEXT"]
ConclusionType = Literal["PRIMARY_CAUSE_IDENTIFIED", "POSSIBLE_CAUSE", "UNDETERMINED"]
