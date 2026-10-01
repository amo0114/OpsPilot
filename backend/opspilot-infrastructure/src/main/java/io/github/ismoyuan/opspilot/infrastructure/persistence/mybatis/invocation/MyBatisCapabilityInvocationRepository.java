package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import io.github.ismoyuan.opspilot.application.capability.CapabilityInvocationRepository;
import io.github.ismoyuan.opspilot.application.capability.InvocationRecord;
import io.github.ismoyuan.opspilot.application.capability.NewInvestigationInvocation;
import io.github.ismoyuan.opspilot.application.capability.NewRecoverySampleInvocation;
import io.github.ismoyuan.opspilot.application.capability.RecoverySampleInvocation;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisCapabilityInvocationRepository implements CapabilityInvocationRepository {

    private final CapabilityInvocationMapper mapper;

    MyBatisCapabilityInvocationRepository(CapabilityInvocationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insertRunningInvestigationCall(NewInvestigationInvocation invocation) {
        InvocationInsert insert = new InvocationInsert(
                invocation.incidentId(),
                invocation.investigationId(),
                invocation.runNo(),
                invocation.capabilityKey(),
                invocation.managedResourceId(),
                invocation.requestSchema().name(),
                invocation.requestSchema().version(),
                invocation.requestPayload(),
                utc(invocation.startedAt()),
                invocation.correlationId());
        mapper.insertRunningInvestigationCall(insert);
        return insert.getId();
    }

    @Override
    public Optional<Long> insertRunningRecoverySample(NewRecoverySampleInvocation invocation) {
        CapabilityInvocationMapper.GeneratedKey key = new CapabilityInvocationMapper.GeneratedKey();
        try {
            mapper.insertRunningRecoverySample(
                    key,
                    new CapabilityInvocationMapper.RecoverySampleInsert(
                            invocation.incidentId(),
                            invocation.recoveryVerificationId(),
                            invocation.criterionKey(),
                            invocation.sampleIndex(),
                            invocation.capabilityKey(),
                            invocation.managedResourceId(),
                            invocation.requestSchema().name(),
                            invocation.requestSchema().version(),
                            invocation.requestPayload(),
                            utc(invocation.startedAt()),
                            invocation.correlationId()));
        } catch (DuplicateKeyException ex) {
            if (String.valueOf(ex.getMessage()).contains("uk_capability_invocation_sample")) {
                return Optional.empty();
            }
            throw ex;
        }
        return Optional.of(key.getId());
    }

    @Override
    public List<RecoverySampleInvocation> findRecoverySamples(long recoveryVerificationId) {
        return mapper.selectRecoverySamples(recoveryVerificationId).stream()
                .map(row -> new RecoverySampleInvocation(
                        row.id(),
                        row.criterionKey(),
                        row.sampleIndex(),
                        row.status(),
                        instant(row.startedAt()),
                        instant(row.finishedAt()),
                        instant(row.observedAt()),
                        row.responseSchemaName(),
                        row.responseSchemaVersion(),
                        row.responsePayload(),
                        row.errorCode()))
                .toList();
    }

    private static Instant instant(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    @Override
    public Optional<Long> findIncidentId(long id) {
        return Optional.ofNullable(mapper.selectIncidentId(id));
    }

    @Override
    public List<Long> findRunningInvestigationCallIds(long incidentId) {
        return mapper.selectRunningInvestigationCallIds(incidentId);
    }

    @Override
    public Optional<InvocationRecord> findByIdForUpdate(long id) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(id))
                .map(row -> new InvocationRecord(
                        row.id(),
                        row.incidentId(),
                        row.investigationId(),
                        row.recoveryVerificationId(),
                        row.runNo(),
                        row.capabilityKey(),
                        row.managedResourceId(),
                        row.status(),
                        row.startedAt().toInstant(ZoneOffset.UTC)));
    }

    @Override
    public boolean markSucceeded(
            long id,
            CapabilitySchema responseSchema,
            String responsePayload,
            String rawResultRef,
            Instant finishedAt,
            long durationMs) {
        return mapper.markSucceeded(
                        id,
                        responseSchema.name(),
                        responseSchema.version(),
                        responsePayload,
                        rawResultRef,
                        utc(finishedAt),
                        durationMs)
                == 1;
    }

    @Override
    public boolean markFailed(long id, ErrorCode errorCode, String safeMessage, Instant finishedAt, long durationMs) {
        return mapper.markFailed(id, errorCode.name(), safeMessage, utc(finishedAt), durationMs) == 1;
    }

    @Override
    public List<String> findGuardedRequestPayloads(
            long investigationId,
            String capabilityKey,
            long managedResourceId,
            CapabilitySchema requestSchema,
            Instant finishedSince) {
        return List.copyOf(mapper.selectGuardedRequestPayloads(
                investigationId,
                capabilityKey,
                managedResourceId,
                requestSchema.name(),
                requestSchema.version(),
                utc(finishedSince)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
