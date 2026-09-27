package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/** database.inspect 的固定检查类型（06 §70）；不存在任意 SQL。 */
public enum InspectionType {
    SERVER_SUMMARY,
    CONNECTION_SUMMARY,
    SLOW_QUERIES,
    LOCK_WAITS
}
