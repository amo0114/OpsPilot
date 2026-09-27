package io.github.ismoyuan.opspilot.infrastructure.remediation;

import io.github.ismoyuan.opspilot.application.remediation.RemediationPlanSuperseder;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * RemediationPlan 表建立前的占位实现：尚不存在任何方案，因此没有需要失效的未执行 Plan，如实不做任何写入。
 * 不是长期 no-op：TASK-062 建表后必须以真实 supersede 替换本类，TASK-067 补真实 Plan 的集成断言（08 TASK-026）。
 */
@Component
class NoRemediationPlanSuperseder implements RemediationPlanSuperseder {

    @Override
    public void supersedeUnexecutedPlans(long incidentId, long newDiagnosisId, Instant at) {
        // 无 Plan 表，无可失效方案
    }
}
