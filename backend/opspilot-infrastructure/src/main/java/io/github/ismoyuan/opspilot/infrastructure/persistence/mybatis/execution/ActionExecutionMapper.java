package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** ActionExecution 插入、查询与条件状态推进（04 §45～§47、§82）。 */
@Mapper
interface ActionExecutionMapper {

    int insertPending(@Param("key") GeneratedKey key, @Param("execution") PendingInsert execution);

    ExecutionRow selectByActionId(@Param("actionId") long actionId);

    List<DispatchableWork.ActionExecution> selectDispatchable();

    RecordRow selectRecord(@Param("id") long id);

    int markRunning(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("context") String context,
            @Param("at") LocalDateTime at);

    int registerReconciliation(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("at") LocalDateTime at,
            @Param("deadline") LocalDateTime deadline);

    int markSucceeded(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("schemaName") String schemaName,
            @Param("schemaVersion") int schemaVersion,
            @Param("payload") String payload,
            @Param("at") LocalDateTime at);

    int markFailed(
            @Param("id") long id,
            @Param("from") String from,
            @Param("expectedVersion") long expectedVersion,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("at") LocalDateTime at);

    int updatePlanFromActive(
            @Param("planId") long planId, @Param("status") String status, @Param("at") LocalDateTime at);

    record RecordRow(
            long id,
            String status,
            long lockVersion,
            long remediationActionId,
            long planId,
            long incidentId,
            String context,
            LocalDateTime startedAt,
            int reconciliationAttemptCount,
            int maxReconciliationAttempts,
            LocalDateTime lastReconciliationAt,
            LocalDateTime reconciliationDeadlineAt) {}

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
