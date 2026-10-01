package io.github.ismoyuan.opspilot.domain.incident;

import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.CANCEL_INCIDENT;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.CONTINUE_INVESTIGATION;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.REQUEST_REMEDIATION;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.START_INVESTIGATION;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.STOP_INVESTIGATION;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentAction.VERIFY_RECOVERY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 08 TASK-086：8 状态 × Stop／待审批／可请求处理建议的全部组合，与 05 §91 前置条件总表独立书写的期望一致；EXECUTING、VERIFYING、
 * RESOLVED、CANCELLED 没有任何用户动作。
 */
class IncidentActionPolicyTest {

    @Test
    void everyStatusAndFactCombinationMatchesThePreconditionTable() {
        for (IncidentStatus status : IncidentStatus.values()) {
            for (boolean stop : new boolean[] {false, true}) {
                for (boolean pending : new boolean[] {false, true}) {
                    for (boolean remediation : new boolean[] {false, true}) {
                        assertThat(IncidentActionPolicy.available(status, stop, pending, remediation))
                                .as("%s stop=%s pending=%s remediation=%s", status, stop, pending, remediation)
                                .containsExactlyElementsOf(expected(status, stop, pending, remediation));
                    }
                }
            }
        }
    }

    @Test
    void examplesFromTheApiContract() {
        // 05 §20 创建响应
        assertThat(IncidentActionPolicy.available(IncidentStatus.CREATED, false, false, false))
                .containsExactly(START_INVESTIGATION, CANCEL_INCIDENT);
        // 05 §23 已诊断且结论可操作
        assertThat(IncidentActionPolicy.available(IncidentStatus.DIAGNOSED, false, false, true))
                .containsExactly(CONTINUE_INVESTIGATION, REQUEST_REMEDIATION, VERIFY_RECOVERY, CANCEL_INCIDENT);
    }

    /** 05 §27～§34、§91 的前置条件，逐动作书写。 */
    private static List<IncidentAction> expected(
            IncidentStatus status, boolean stop, boolean pending, boolean remediation) {
        List<IncidentAction> actions = new ArrayList<>();
        switch (status) {
            case CREATED -> actions.add(START_INVESTIGATION);
            case INVESTIGATING -> {
                if (!stop) {
                    actions.add(STOP_INVESTIGATION);
                }
            }
            case DIAGNOSED -> {
                if (!pending) {
                    actions.add(CONTINUE_INVESTIGATION);
                }
                if (remediation) {
                    actions.add(REQUEST_REMEDIATION);
                }
                actions.add(VERIFY_RECOVERY);
            }
            default -> {}
        }
        if (List.of(
                        IncidentStatus.CREATED,
                        IncidentStatus.INVESTIGATING,
                        IncidentStatus.DIAGNOSED,
                        IncidentStatus.AWAITING_APPROVAL)
                .contains(status)) {
            actions.add(CANCEL_INCIDENT);
        }
        return actions;
    }
}
