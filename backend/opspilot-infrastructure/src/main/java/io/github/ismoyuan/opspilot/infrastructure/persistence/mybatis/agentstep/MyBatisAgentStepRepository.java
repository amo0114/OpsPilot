package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.agentstep;

import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRepository;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStep;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStepStatus;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.infrastructure.ai.AiProtocolCodec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 输出按 v1 协议编码保存（只有结构化 Intent，不含 Prompt 或思维链，07 §79）。 */
@Repository
class MyBatisAgentStepRepository implements AgentStepRepository {

    private final AgentStepMapper mapper;
    private final AiProtocolCodec codec = new AiProtocolCodec();

    MyBatisAgentStepRepository(AgentStepMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentStep insertRunning(long incidentId, long investigationId, int runNo, Instant startedAt) {
        AgentStepInsert insert = new AgentStepInsert(incidentId, investigationId, runNo, utc(startedAt));
        mapper.insertRunning(insert);
        return toDomain(mapper.selectByIdForUpdate(insert.getId()));
    }

    @Override
    public Optional<Long> findIncidentId(long stepId) {
        return Optional.ofNullable(mapper.selectIncidentId(stepId));
    }

    @Override
    public Optional<AgentStep> findByIdForUpdate(long stepId) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(stepId)).map(MyBatisAgentStepRepository::toDomain);
    }

    @Override
    public void markSucceeded(
            AgentStep running,
            InvestigationStepResponse response,
            AiCallMetadata metadata,
            long latencyMs,
            Instant finishedAt) {
        int updated = mapper.markSucceeded(
                running.id(),
                response.intentType().name(),
                codec.encode(response),
                metadata.modelProvider(),
                metadata.modelName(),
                metadata.promptTemplateVersion(),
                metadata.promptTokens(),
                metadata.completionTokens(),
                latencyMs,
                utc(finishedAt));
        requireRunning(running, updated);
    }

    @Override
    public void markFailed(
            AgentStep running, ErrorCode errorCode, String safeMessage, long latencyMs, Instant finishedAt) {
        requireRunning(
                running, mapper.markFailed(running.id(), errorCode.name(), safeMessage, latencyMs, utc(finishedAt)));
    }

    private static void requireRunning(AgentStep step, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Agent step is no longer RUNNING: id=" + step.id());
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static AgentStep toDomain(AgentStepRow row) {
        return new AgentStep(
                row.id(),
                row.incidentId(),
                row.investigationId(),
                row.runNo(),
                row.stepNo(),
                AgentStepStatus.valueOf(row.status()),
                row.startedAt().toInstant(ZoneOffset.UTC));
    }
}
