package io.github.ismoyuan.opspilot.application.diagnosis;

import io.github.ismoyuan.opspilot.domain.diagnosis.Diagnosis;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.time.Instant;

/**
 * Diagnosis 只插入端口（04 §32～§36、DB-INV-006）：没有更新或删除方法。调用方须已锁定所属 Investigation，
 * 使版本号分配串行；UNIQUE(investigation_id, version_no) 做最后保护。
 *
 * <p>版本号以普通读取的最大值＋1 分配（TASK-026），因此所在事务的第一次一致性读必须发生在取得 Incident/Investigation 行锁之后：
 * DiagnosisApplicationService 独立调用与 InvestigationTerminator 先加锁；结果事务（AgentStepRecorder）在事务外读取 Step 所属
 * Incident。新增调用链须遵守同一前提。
 */
public interface DiagnosisRepository {

    /**
     * 以该 Investigation 当前最大 version_no + 1 插入，并在同一事务写入 draft.evidenceIds 的冻结引用。
     * 草稿须已通过 {@link DiagnosisDraft#checkReferences}。
     */
    Diagnosis insert(
            long investigationId, int runNo, DiagnosisDraft draft, TerminationReason terminationReason, Instant at);
}
