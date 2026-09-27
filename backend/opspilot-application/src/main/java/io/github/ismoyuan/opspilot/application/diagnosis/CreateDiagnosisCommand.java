package io.github.ismoyuan.opspilot.application.diagnosis;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.util.Objects;

/**
 * 收束本轮调查并冻结新 Diagnosis（08 TASK-026）。调用方是调查 Intent 分派与确定性收束（TASK-040/042），没有公开写接口。
 *
 * @param runNo 草稿所属 run，必须仍是当前 run
 */
public record CreateDiagnosisCommand(
        long incidentId, int runNo, DiagnosisDraft draft, TerminationReason terminationReason) {

    public CreateDiagnosisCommand {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(terminationReason, "terminationReason");
    }
}
