package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.domain.system.SystemStatus;

/** 业务系统列表项（05 §15）；resourceCount 统计该系统全部资源。 */
public record SystemSummaryView(
        String systemKey, String name, String environment, SystemStatus status, long resourceCount) {}
