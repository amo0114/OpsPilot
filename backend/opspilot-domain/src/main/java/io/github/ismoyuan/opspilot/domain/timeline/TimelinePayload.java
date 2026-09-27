package io.github.ismoyuan.opspilot.domain.timeline;

/**
 * 时间线事件的强类型载荷（04 §58、§69）。持久化时 schemaName/schemaVersion 与 record 组件一起写入 JSON，
 * 组件名不得与这两个键重名。
 */
public interface TimelinePayload {

    String schemaName();

    int schemaVersion();
}
