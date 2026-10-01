package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.capability.RecoverySampleInvocation;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1.SnapshotCriterion;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 持久化样本槽位 → 样本（04 §18～§19、§80、06 §113）：Runner 求值与详情展示共用同一读取，展示的值就是判定所用的值。成功样本按注册
 * 投影取值，时间取 Observation 的 observed_at（没有时取完成时间）；失败或未完成的槽位没有值，不补造。
 */
@Component
public class RecoverySampleReader {

    private final SchemaCodecRegistry codecs;

    public RecoverySampleReader(SchemaCodecRegistry codecs) {
        this.codecs = codecs;
    }

    public List<RecoverySample> samples(SnapshotCriterion criterion, List<RecoverySampleInvocation> slots) {
        RecoveryCriterionV1 definition = criterion.criterion();
        List<RecoverySample> samples = new ArrayList<>();
        for (RecoverySampleInvocation slot : slots) {
            RecoverySample.Status status = switch (slot.status()) {
                case "SUCCEEDED" -> RecoverySample.Status.SUCCEEDED;
                case "FAILED" -> RecoverySample.Status.FAILED;
                default -> RecoverySample.Status.RUNNING;
            };
            if (status != RecoverySample.Status.SUCCEEDED) {
                samples.add(new RecoverySample(slot.sampleIndex(), slot.id(), status, null, null));
                continue;
            }
            Instant sampledAt = slot.observedAt() != null ? slot.observedAt() : slot.finishedAt();
            samples.add(new RecoverySample(
                    slot.sampleIndex(),
                    slot.id(),
                    status,
                    sampledAt,
                    project(slot, definition.field(), criterion.consumerGroup())));
        }
        return samples;
    }

    private ProjectedValue project(RecoverySampleInvocation slot, RecoveryField field, String consumerGroup) {
        if (slot.responseSchemaName() == null
                || slot.responseSchemaVersion() == null
                || slot.responsePayload() == null) {
            return new ProjectedValue.Unknown(ProjectedValue.Unknown.RESULT_MISMATCH);
        }
        Class<? extends CapabilityResult> type = RecoveryProjection.resultType(field.capability());
        try {
            CapabilityResult result = codecs.decode(
                    slot.responseSchemaName(), slot.responseSchemaVersion(), slot.responsePayload(), type);
            return RecoveryProjection.project(result, field, consumerGroup);
        } catch (SchemaPayloadException ex) {
            return new ProjectedValue.Unknown(ProjectedValue.Unknown.RESULT_MISMATCH);
        }
    }
}
