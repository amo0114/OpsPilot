package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** RecoveryVerification 读取与带期望状态、版本的条件推进（04 §50、§80）；终态没有再改写的语句。 */
@Mapper
interface RecoveryVerificationMapper {

    VerificationRow selectById(@Param("id") long id);

    List<DispatchableWork.RecoveryVerification> selectDispatchable();

    int insertPending(@Param("key") GeneratedKey key, @Param("v") PendingInsert verification);

    Integer selectMaxVerificationNo(@Param("incidentId") long incidentId);

    int countActive(@Param("incidentId") long incidentId);

    int markRunning(
            @Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("at") LocalDateTime at);

    int markFinished(
            @Param("id") long id,
            @Param("from") String from,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("summary") String summary,
            @Param("payload") String payload,
            @Param("at") LocalDateTime at);

    record PendingInsert(
            long incidentId,
            Long actionExecutionId,
            long managedResourceId,
            long recoveryPolicyId,
            int recoveryPolicyVersion,
            String policySnapshot,
            int verificationNo,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt) {}

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

    record VerificationRow(
            long id,
            long incidentId,
            int verificationNo,
            String status,
            String policySnapshot,
            LocalDateTime deadlineAt,
            LocalDateTime startedAt,
            long lockVersion) {}
}
