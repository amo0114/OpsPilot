package io.github.ismoyuan.opspilot.application.timeline;

/**
 * 某 Incident 追加了 Timeline 事件（08 TASK-088）。只是唤醒信号，不携带事件内容：监听方须在事务提交之后才处理，再从数据库按游标读取
 * 已提交的事件（07 §72），不能把它当作事件本身或提交顺序。
 */
public record IncidentTimelineAppended(long incidentId) {}
