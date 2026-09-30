package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import io.github.ismoyuan.opspilot.application.approval.PendingApprovalCanceller;
import io.github.ismoyuan.opspilot.application.approval.PendingApprovalQuery;
import io.github.ismoyuan.opspilot.application.remediation.RemediationPlanSuperseder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Repository;

/**
 * 方案与审批在 Incident 生命周期事务中的联动（08 TASK-062，替换 TASK-019/026 的占位）：新 Diagnosis 使未执行 Plan 失效；取消等待审批的
 * Incident 撤回 PENDING Approval 与未执行 Plan；继续调查前核对是否仍有 PENDING Approval。调用方已持有 Incident 行锁。
 */
@Repository
class MyBatisRemediationRecords implements RemediationPlanSuperseder, PendingApprovalCanceller, PendingApprovalQuery {

    private final RemediationMapper mapper;

    MyBatisRemediationRecords(RemediationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void supersedeUnexecutedPlans(long incidentId, long newDiagnosisId, Instant at) {
        mapper.supersedeActivePlans(incidentId, utc(at));
    }

    /** 决定人为取消者（decided_by NOT NULL，01 §25 留痕）；找不到 PENDING Approval 说明状态与数据不一致，抛出使取消整体回滚。 */
    @Override
    public void cancelPendingApprovalAndPlans(long incidentId, Instant at, String actor) {
        if (mapper.cancelPendingApprovals(incidentId, utc(at), actor) == 0) {
            throw new IllegalStateException("AWAITING_APPROVAL incident without a pending approval: " + incidentId);
        }
        mapper.cancelActivePlans(incidentId, utc(at));
    }

    @Override
    public boolean existsPending(long incidentId) {
        return mapper.existsPendingApproval(incidentId);
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
