"""Typed capability arguments, request variants and descriptors (06 §21-§22).

No free-form argument map, no PromQL/LogQL/SQL/shell, and no service.restart in investigation.
"""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints

from opspilot_ai.protocol.v1.common import (
    WHITE_SPACE_RANGES,
    ExactInt,
    Id,
    ProtocolModel,
    ResourceKey,
    Text500,
    Unique,
    char_class,
)

WindowKey = Literal["INCIDENT_CONTEXT", "LAST_15_MIN", "LAST_30_MIN", "LAST_60_MIN"]
LogSeverity = Literal["ERROR", "WARN", "INFO", "DEBUG"]
InspectionType = Literal["SERVER_SUMMARY", "CONNECTION_SUMMARY", "SLOW_QUERIES", "LOCK_WAITS"]
MetricKey = Annotated[
    str, StringConstraints(max_length=128, pattern=r"^[a-z0-9_]+(\.[a-z0-9_]+)*$")
]
_CONTROL = ((0x00, 0x1F), (0x7F, 0x7F))
# Controls and White_Space; U+0009-000D and U+0020 fold into the first range
_CONTROL_OR_SPACE = ((0x00, 0x20), (0x7F, 0x7F), *WHITE_SPACE_RANGES[2:])
KEYWORD_PATTERN = (
    f"^[^{char_class(_CONTROL)}]*[^{char_class(_CONTROL_OR_SPACE)}][^{char_class(_CONTROL)}]*$"
)
Keyword = Annotated[str, StringConstraints(min_length=1, max_length=64, pattern=KEYWORD_PATTERN)]

MAX_KEYWORDS = 5
MAX_KEYWORD_LENGTH = 64
LIMIT_MIN = 1
LIMIT_MAX = 20


class MetricsQueryArgumentsV1(ProtocolModel):
    metric_key: MetricKey
    window_key: WindowKey
    compare_previous_window: bool


class LogsSearchArgumentsV1(ProtocolModel):
    window_key: WindowKey
    severity: Annotated[list[LogSeverity], Field(min_length=1), Unique]
    keywords: Annotated[list[Keyword], Field(max_length=MAX_KEYWORDS), Unique]


class DatabaseInspectArgumentsV1(ProtocolModel):
    inspection_type: InspectionType
    limit: Annotated[int, Field(ge=LIMIT_MIN, le=LIMIT_MAX)] | None = None


class CacheInspectArgumentsV1(ProtocolModel):
    """No AI-controlled arguments; the wire value is an explicit {}."""


class QueueInspectArgumentsV1(ProtocolModel):
    """No AI-controlled arguments; the wire value is an explicit {}."""


class ServiceInspectArgumentsV1(ProtocolModel):
    """No AI-controlled arguments; the wire value is an explicit {}."""


class _Request(ProtocolModel):
    resource_id: Id
    purpose: Text500


class MetricsQueryRequest(_Request):
    capability_key: Literal["metrics.query"]
    arguments: MetricsQueryArgumentsV1


class LogsSearchRequest(_Request):
    capability_key: Literal["logs.search"]
    arguments: LogsSearchArgumentsV1


class DatabaseInspectRequest(_Request):
    capability_key: Literal["database.inspect"]
    arguments: DatabaseInspectArgumentsV1


class CacheInspectRequest(_Request):
    capability_key: Literal["cache.inspect"]
    arguments: CacheInspectArgumentsV1


class QueueInspectRequest(_Request):
    capability_key: Literal["queue.inspect"]
    arguments: QueueInspectArgumentsV1


class ServiceInspectRequest(_Request):
    capability_key: Literal["service.inspect"]
    arguments: ServiceInspectArgumentsV1


RequestCapability = Annotated[
    MetricsQueryRequest
    | LogsSearchRequest
    | DatabaseInspectRequest
    | CacheInspectRequest
    | QueueInspectRequest
    | ServiceInspectRequest,
    Field(discriminator="capability_key"),
]


class _Descriptor(ProtocolModel):
    resource_id: Id
    resource_key: ResourceKey


class MetricsQueryDescriptor(_Descriptor):
    descriptor_type: Literal["METRICS_QUERY"]
    key: Literal["metrics.query"]
    metric_keys: Annotated[list[MetricKey], Field(min_length=1), Unique]
    window_keys: Annotated[list[WindowKey], Field(min_length=1), Unique]
    supports_previous_window_comparison: bool


class LogsSearchDescriptor(_Descriptor):
    descriptor_type: Literal["LOGS_SEARCH"]
    key: Literal["logs.search"]
    window_keys: Annotated[list[WindowKey], Field(min_length=1), Unique]
    severities: Annotated[list[LogSeverity], Field(min_length=1), Unique]
    max_keywords: Annotated[Literal[5], ExactInt]
    max_keyword_length: Annotated[Literal[64], ExactInt]


class DatabaseInspectDescriptor(_Descriptor):
    descriptor_type: Literal["DATABASE_INSPECT"]
    key: Literal["database.inspect"]
    inspection_types: Annotated[list[InspectionType], Field(min_length=1), Unique]
    limit_min: Annotated[Literal[1], ExactInt]
    limit_max: Annotated[Literal[20], ExactInt]


class CacheInspectDescriptor(_Descriptor):
    descriptor_type: Literal["CACHE_INSPECT"]
    key: Literal["cache.inspect"]


class QueueInspectDescriptor(_Descriptor):
    descriptor_type: Literal["QUEUE_INSPECT"]
    key: Literal["queue.inspect"]


class ServiceInspectDescriptor(_Descriptor):
    descriptor_type: Literal["SERVICE_INSPECT"]
    key: Literal["service.inspect"]


CapabilityDescriptor = Annotated[
    MetricsQueryDescriptor
    | LogsSearchDescriptor
    | DatabaseInspectDescriptor
    | CacheInspectDescriptor
    | QueueInspectDescriptor
    | ServiceInspectDescriptor,
    Field(discriminator="descriptor_type"),
]
