package io.github.ismoyuan.opspilot.domain.observation;

/** 观测粗分类（04 §25）；具体结构由 schemaName/schemaVersion 决定。 */
public enum ObservationKind {
    METRIC,
    LOG_PATTERN,
    SERVICE_STATUS,
    DATABASE_STATUS,
    CACHE_STATUS,
    QUEUE_STATUS,
    OTHER
}
