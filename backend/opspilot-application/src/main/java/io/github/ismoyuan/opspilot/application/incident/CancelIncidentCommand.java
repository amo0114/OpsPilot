package io.github.ismoyuan.opspilot.application.incident;

/**
 * 取消故障处理（05 §33）。
 *
 * @param reason 可为空，最多 {@link #REASON_MAX} 字符
 */
public record CancelIncidentCommand(String incidentKey, long expectedVersion, String reason, String actor) {

    public static final int REASON_MAX = 500;
}
