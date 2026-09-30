package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** ActionExecution 插入与查询（04 §45～§47）；状态推进语句随 Worker 加入（TASK-071 起）。 */
@Mapper
interface ActionExecutionMapper {

    int insertPending(@Param("key") GeneratedKey key, @Param("execution") PendingInsert execution);

    ExecutionRow selectByActionId(@Param("actionId") long actionId);

    List<DispatchableWork.ActionExecution> selectPending();

    record ExecutionRow(long id, String status) {}

    record PendingInsert(
            long remediationActionId,
            long approvalRequestId,
            String idempotencyKey,
            String executorKey,
            long recoveryPolicyId,
            int recoveryPolicyVersion,
            String recoveryPolicySnapshot,
            String executionContextSchemaName,
            int executionContextSchemaVersion,
            String executionContextPayload,
            int maxReconciliationAttempts,
            String correlationId,
            LocalDateTime at) {}

    /** MyBatis 回填自增主键的载体。 */
    final class GeneratedKey {

        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
