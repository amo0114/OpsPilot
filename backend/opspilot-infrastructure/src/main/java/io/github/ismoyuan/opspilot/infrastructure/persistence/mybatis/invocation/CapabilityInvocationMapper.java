package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CapabilityInvocationMapper {

    int insertRunningInvestigationCall(@Param("i") InvocationInsert invocation);

    int insertRunningRecoverySample(@Param("key") GeneratedKey key, @Param("s") RecoverySampleInsert sample);

    List<RecoverySampleRow> selectRecoverySamples(@Param("verificationId") long verificationId);

    Long selectIncidentId(@Param("id") long id);

    InvocationRow selectByIdForUpdate(@Param("id") long id);

    List<Long> selectRunningInvestigationCallIds(@Param("incidentId") long incidentId);

    int markSucceeded(
            @Param("id") long id,
            @Param("responseSchemaName") String responseSchemaName,
            @Param("responseSchemaVersion") int responseSchemaVersion,
            @Param("responsePayload") String responsePayload,
            @Param("rawResultRef") String rawResultRef,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("durationMs") long durationMs);

    int markFailed(
            @Param("id") long id,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("durationMs") long durationMs);

    List<String> selectGuardedRequestPayloads(
            @Param("investigationId") long investigationId,
            @Param("capabilityKey") String capabilityKey,
            @Param("managedResourceId") long managedResourceId,
            @Param("requestSchemaName") String requestSchemaName,
            @Param("requestSchemaVersion") int requestSchemaVersion,
            @Param("finishedSince") LocalDateTime finishedSince);

    record RecoverySampleInsert(
            long incidentId,
            long recoveryVerificationId,
            String criterionKey,
            int sampleIndex,
            String capabilityKey,
            long managedResourceId,
            String requestSchemaName,
            int requestSchemaVersion,
            String requestPayload,
            LocalDateTime startedAt,
            String correlationId) {}

    record RecoverySampleRow(
            long id,
            String criterionKey,
            int sampleIndex,
            String status,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime observedAt,
            String responseSchemaName,
            Integer responseSchemaVersion,
            String responsePayload,
            String errorCode) {}

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
