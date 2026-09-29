package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.raw.RawResultStore;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedResult;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 成功 OBSERVE 结果的唯一组装路径（06 §31～§32、§119、07 §59）：Provider 结果 → {@link Sanitizer} → 较大原始结果写
 * {@link RawResultStore} → 按结果 Schema 编码为 response_payload → {@link ObservationExtractor} → {@link InvocationOutcome.Succeeded}，
 * 再由 {@link CapabilityResultRecorder} 在结果事务中落账。各 Provider（TASK-052～057）只产出真实结果并交给这里，不自行拼装载荷或
 * Observation，外部数据因此不会绕过脱敏进入持久化、Observation 或 AI。
 *
 * <p>在数据库事务之外调用。任一步失败即抛出，由执行服务记为 CAPABILITY_INVOCATION_FAILED，不产生 Observation（CAP-INV-006）。
 * 原始结果文件在结果事务之前写入；该调用随后未落账（如已被中断标记）时文件没有引用，内容已脱敏。
 */
@Service
public class ObserveResultPipeline {

    private final Sanitizer sanitizer;
    private final RawResultStore rawResults;
    private final SchemaCodecRegistry codecs;
    private final ObservationExtractor extractor;

    public ObserveResultPipeline(
            Sanitizer sanitizer,
            RawResultStore rawResults,
            SchemaCodecRegistry codecs,
            ObservationExtractor extractor) {
        this.sanitizer = sanitizer;
        this.rawResults = rawResults;
        this.codecs = codecs;
        this.extractor = extractor;
    }

    /**
     * @param definition 所执行的能力；结果 Schema 必须与其 Registry 定义一致
     * @param rawProviderResult Provider 的较大原始返回（如完整点序列、日志行），脱敏后另存；没有时为空
     * @param observedAt Provider 取得数据的真实时刻
     * @throws IllegalArgumentException 结果类型与能力不符
     */
    public InvocationOutcome.Succeeded succeeded(
            CapabilityDefinition definition,
            long incidentId,
            long invocationId,
            CapabilityResult result,
            String rawProviderResult,
            Instant observedAt) {
        CapabilitySchema schema = result.resultSchema();
        if (!schema.equals(definition.resultSchema())) {
            throw new IllegalArgumentException("result schema does not match capability "
                    + definition.key().key());
        }
        SanitizedResult sanitized = sanitizer.sanitizeResult(result);
        String payload = codecs.encode(schema.name(), schema.version(), sanitized.value());
        List<ObservationDraft> observations = extractor.extract(sanitized, observedAt);
        // 唯一的副作用放在最后：编码或提取失败时不留下文件
        String rawResultRef = rawProviderResult == null
                ? null
                : rawResults.store(incidentId, invocationId, sanitizer.sanitizeRaw(rawProviderResult));
        return new InvocationOutcome.Succeeded(schema, payload, rawResultRef, observations);
    }
}
