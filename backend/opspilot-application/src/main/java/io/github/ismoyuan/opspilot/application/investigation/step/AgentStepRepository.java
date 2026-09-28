package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStep;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AgentStepRecord 持久化端口（04 §59～§61）。只有登记 RUNNING 与从 RUNNING 条件写入终态；没有删除或改写已终结记录的方法。
 * 调用方须已按 Incident → Investigation 锁序持锁，使 step_no 分配串行。
 */
public interface AgentStepRepository {

    /**
     * step_no 取该 Investigation 现有最大值＋1（跨 run 不重置），run_no 为准入时的快照。调用方须已持有该 Investigation 的行锁，
     * 且本事务在取得该锁之前没有做过一致性读（准入事务只做加锁读取），分配只锁新插入的行，不阻塞其他调查的准入。
     */
    AgentStep insertRunning(long incidentId, long investigationId, int runNo, Instant startedAt);

    /** 不加锁读取该 Incident 下仍为 RUNNING 的 Step id（按 id 升序）。 */
    List<Long> findRunningStepIds(long incidentId);

    /** 不加锁读取 Step 所属 Incident（创建后不变），用于按锁序先锁 Incident。 */
    Optional<Long> findIncidentId(long stepId);

    Optional<AgentStep> findByIdForUpdate(long stepId);

    /**
     * RUNNING → SUCCEEDED：保存 intent_type、结构化提议与处置（{@link AgentStepOutput}，output_schema_version=1）、调用元数据与耗时。
     * 不保存 Prompt 或思维链。
     *
     * @throws IllegalStateException 该 Step 已不是 RUNNING
     */
    void markSucceeded(
            AgentStep running, AgentStepOutput output, AiCallMetadata metadata, long latencyMs, Instant finishedAt);

    /**
     * RUNNING → FAILED：保存错误码与固定的、已脱敏的错误文案。
     *
     * @throws IllegalStateException 该 Step 已不是 RUNNING
     */
    void markFailed(AgentStep running, ErrorCode errorCode, String safeMessage, long latencyMs, Instant finishedAt);

    /**
     * 启动恢复（07 §54）：startedBefore 之前开始、仍为 RUNNING 的 Step → FAILED/PROCESS_INTERRUPTED，耗时未知记空。
     * 不改连续 AI 失败计数。
     *
     * @return 标记条数
     */
    int markInterrupted(Instant startedBefore, String safeMessage, Instant finishedAt);
}
