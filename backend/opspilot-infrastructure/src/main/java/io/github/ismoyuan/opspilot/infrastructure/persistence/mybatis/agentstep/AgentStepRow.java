package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.agentstep;

import java.time.LocalDateTime;

/** agent_step_record 的身份与状态列；时间为 UTC。 */
record AgentStepRow(
        long id,
        long incidentId,
        long investigationId,
        int runNo,
        int stepNo,
        String status,
        LocalDateTime startedAt) {}
