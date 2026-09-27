package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import java.time.Instant;
import java.util.List;

/**
 * 创建故障（05 §20）。createdSource/createdBy 由入口给定：人工 API 为 MANUAL/demo-user，Fault Lab 为 FAULT_LAB。
 *
 * @param description 可为空
 * @param startedAt 可为空，为空时取检测时间
 * @param affectedResourceKeys 可为空，视为空列表；必须全部属于该系统且不重复
 */
public record CreateIncidentCommand(
        String systemKey,
        String title,
        String description,
        String impactSummary,
        Instant startedAt,
        List<String> affectedResourceKeys,
        IncidentSource createdSource,
        String createdBy) {}
