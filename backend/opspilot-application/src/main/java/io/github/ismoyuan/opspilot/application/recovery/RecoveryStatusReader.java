package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.capability.CapabilityInvocationRepository;
import io.github.ismoyuan.opspilot.application.capability.RecoverySampleInvocation;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1.SnapshotCriterion;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationQuery.VerificationSnapshot;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 读取一个 Incident 最新一次 Verification 的恢复情况（08 TASK-085）。只读，须在调用方的只读事务中执行，与其他详情字段同一快照。
 * 终态：检查的结果、原因与样本槽位取自 result_payload（判定时刻的事实）；进行中：按冻结快照顺序列出全部检查及已登记样本，不提前给出
 * 结论。样本值经与 Runner 相同的 {@link RecoverySampleReader} 取得。
 */
@Service
public class RecoveryStatusReader {

    private final RecoveryVerificationQuery query;
    private final CapabilityInvocationRepository invocations;
    private final RecoverySampleReader sampleReader;
    private final SchemaCodecRegistry codecs;

    public RecoveryStatusReader(
            RecoveryVerificationQuery query,
            CapabilityInvocationRepository invocations,
            RecoverySampleReader sampleReader,
            SchemaCodecRegistry codecs) {
        this.query = query;
        this.invocations = invocations;
        this.sampleReader = sampleReader;
        this.codecs = codecs;
    }

    public Optional<RecoveryStatusView> latest(long incidentId) {
        return query.findLatest(incidentId).map(this::view);
    }

    private RecoveryStatusView view(VerificationSnapshot verification) {
        RecoveryPolicySnapshotV1 snapshot = codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME,
                RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                verification.policySnapshot(),
                RecoveryPolicySnapshotV1.class);
        Map<String, Map<Long, RecoveryStatusView.Sample>> samples =
                samples(snapshot, invocations.findRecoverySamples(verification.id()));
        List<RecoveryStatusView.Check> checks =
                verification.status().terminal() ? decidedChecks(verification, samples) : openChecks(snapshot, samples);
        return new RecoveryStatusView(
                verification.verificationNo(),
                verification.status(),
                verification.resultSummary(),
                verification.resourceKey(),
                verification.resourceName(),
                snapshot.policyKey(),
                snapshot.policyName(),
                snapshot.policyVersion(),
                verification.actionExecutionId() != null,
                verification.deadlineAt(),
                verification.startedAt(),
                verification.finishedAt(),
                checks);
    }

    /** 终态：检查、原因与样本槽位取自结果载荷；值与错误码按调用 id 取自持久化槽位。 */
    private List<RecoveryStatusView.Check> decidedChecks(
            VerificationSnapshot verification, Map<String, Map<Long, RecoveryStatusView.Sample>> samples) {
        RecoveryVerificationResultV1 result = codecs.decode(
                RecoveryVerificationResultV1.SCHEMA_NAME,
                RecoveryVerificationResultV1.SCHEMA_VERSION,
                verification.resultPayload(),
                RecoveryVerificationResultV1.class);
        return result.checks().stream()
                .map(check -> {
                    Map<Long, RecoveryStatusView.Sample> slots = samples.getOrDefault(check.criterionKey(), Map.of());
                    return new RecoveryStatusView.Check(
                            check.criterionKey(),
                            check.name(),
                            check.required(),
                            check.result(),
                            check.reason(),
                            check.samples().stream()
                                    .map(decided -> decided(decided, slots.get(decided.invocationId())))
                                    .toList());
                })
                .toList();
    }

    /** 判定时刻的槽位状态与时间；成功样本附带取值，失败样本附带错误码。 */
    private static RecoveryStatusView.Sample decided(
            RecoveryVerificationResultV1.Sample decided, RecoveryStatusView.Sample slot) {
        boolean succeeded = decided.status() == RecoverySample.Status.SUCCEEDED;
        boolean failed = decided.status() == RecoverySample.Status.FAILED;
        return new RecoveryStatusView.Sample(
                decided.sampleIndex(),
                decided.status(),
                decided.sampledAt(),
                succeeded && slot != null ? slot.value() : null,
                failed && slot != null ? slot.errorCode() : null);
    }

    /** 进行中：快照顺序的全部检查，尚无结论，只列已登记样本（按序号）。 */
    private static List<RecoveryStatusView.Check> openChecks(
            RecoveryPolicySnapshotV1 snapshot, Map<String, Map<Long, RecoveryStatusView.Sample>> samples) {
        return snapshot.criteria().stream()
                .map(SnapshotCriterion::criterion)
                .map(definition -> new RecoveryStatusView.Check(
                        definition.criterionKey(),
                        definition.name(),
                        definition.required(),
                        null,
                        null,
                        List.copyOf(samples.getOrDefault(definition.criterionKey(), Map.of())
                                .values())))
                .toList();
    }

    /** 每项检查的持久化槽位（按序号）→ 展示样本，以调用 id 为键。 */
    private Map<String, Map<Long, RecoveryStatusView.Sample>> samples(
            RecoveryPolicySnapshotV1 snapshot, List<RecoverySampleInvocation> slots) {
        Map<String, Map<Long, RecoveryStatusView.Sample>> byCriterion = new HashMap<>();
        for (SnapshotCriterion criterion : snapshot.criteria()) {
            String key = criterion.criterion().criterionKey();
            List<RecoverySampleInvocation> own = slots.stream()
                    .filter(slot -> slot.criterionKey().equals(key))
                    .toList();
            Map<Long, String> errors = new HashMap<>();
            own.stream()
                    .filter(slot -> slot.errorCode() != null)
                    .forEach(slot -> errors.put(slot.id(), slot.errorCode()));
            Map<Long, RecoveryStatusView.Sample> samples = new LinkedHashMap<>();
            for (RecoverySample sample : sampleReader.samples(criterion, own)) {
                samples.put(
                        sample.invocationId(),
                        new RecoveryStatusView.Sample(
                                sample.sampleIndex(),
                                sample.status(),
                                sample.sampledAt(),
                                sample.value(),
                                sample.status() == RecoverySample.Status.FAILED
                                        ? errors.get(sample.invocationId())
                                        : null));
            }
            byCriterion.put(key, samples);
        }
        return byCriterion;
    }
}
