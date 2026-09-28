package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CapabilityInvocationMapper {

    int insertRunningInvestigationCall(@Param("i") InvocationInsert invocation);

    InvocationRow selectByIdForUpdate(@Param("id") long id);

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
}
