package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/** 受控时间窗口（06 §42）；具体范围由 Java 解析与限制。 */
public enum WindowKey {
    INCIDENT_CONTEXT,
    LAST_15_MIN,
    LAST_30_MIN,
    LAST_60_MIN
}
