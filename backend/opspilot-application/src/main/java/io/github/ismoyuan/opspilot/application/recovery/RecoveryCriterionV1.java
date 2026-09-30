package io.github.ismoyuan.opspilot.application.recovery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;

/**
 * 一项恢复检查（06 §113）：按 capabilityKey 判别，arguments 是该能力的强类型输入，目标以同系统的 resourceKey 表示。
 * 只有存在注册投影的 OBSERVE 能力可作判据（{@link RecoveryField}）；其余能力键在解码时即被拒绝。
 * 目标资源归属、绑定与唯一 Provider 在激活前由 Java 校验（TASK-076），这里只保证结构与谓词/字段相容。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "capabilityKey")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecoveryCriterionV1.MetricsQuery.class, name = "metrics.query"),
    @JsonSubTypes.Type(value = RecoveryCriterionV1.QueueInspect.class, name = "queue.inspect"),
    @JsonSubTypes.Type(value = RecoveryCriterionV1.ServiceInspect.class, name = "service.inspect")
})
public sealed interface RecoveryCriterionV1 {

    /** name 上限（本批取值，与 Remediation title 一致）。 */
    int NAME_MAX = 200;

    String criterionKey();

    String name();

    String targetResourceKey();

    CapabilityArguments arguments();

    RecoverySamplingV1 sampling();

    RecoveryPredicateV1 predicate();

    Boolean required();

    /** 由变体决定，线上以 capabilityKey 判别属性出现。 */
    @JsonIgnore
    CapabilityKey capabilityKey();

    /** 谓词引用的注册投影（构造时已保证存在）。 */
    @JsonIgnore
    default RecoveryField field() {
        return RecoveryField.find(capabilityKey(), predicate().field()).orElseThrow();
    }

    record MetricsQuery(
            String criterionKey,
            String name,
            String targetResourceKey,
            MetricsQueryArgumentsV1 arguments,
            RecoverySamplingV1 sampling,
            RecoveryPredicateV1 predicate,
            Boolean required)
            implements RecoveryCriterionV1 {

        public MetricsQuery {
            common(CapabilityKey.METRICS_QUERY, criterionKey, name, targetResourceKey, arguments, sampling, predicate);
            RecoveryChecks.required("required", required);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.METRICS_QUERY;
        }
    }

    record QueueInspect(
            String criterionKey,
            String name,
            String targetResourceKey,
            QueueInspectArgumentsV1 arguments,
            RecoverySamplingV1 sampling,
            RecoveryPredicateV1 predicate,
            Boolean required)
            implements RecoveryCriterionV1 {

        public QueueInspect {
            common(CapabilityKey.QUEUE_INSPECT, criterionKey, name, targetResourceKey, arguments, sampling, predicate);
            RecoveryChecks.required("required", required);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.QUEUE_INSPECT;
        }
    }

    record ServiceInspect(
            String criterionKey,
            String name,
            String targetResourceKey,
            ServiceInspectArgumentsV1 arguments,
            RecoverySamplingV1 sampling,
            RecoveryPredicateV1 predicate,
            Boolean required)
            implements RecoveryCriterionV1 {

        public ServiceInspect {
            common(
                    CapabilityKey.SERVICE_INSPECT,
                    criterionKey,
                    name,
                    targetResourceKey,
                    arguments,
                    sampling,
                    predicate);
            RecoveryChecks.required("required", required);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.SERVICE_INSPECT;
        }
    }

    /** 无参数能力也必须显式给出 {}。谓词必须与字段类型相容（08 TASK-076 “Predicate compatible with Result Schema”）。 */
    private static void common(
            CapabilityKey capability,
            String criterionKey,
            String name,
            String targetResourceKey,
            CapabilityArguments arguments,
            RecoverySamplingV1 sampling,
            RecoveryPredicateV1 predicate) {
        RecoveryChecks.criterionKey(criterionKey);
        RecoveryChecks.text("name", name, NAME_MAX);
        RecoveryChecks.resourceKey(targetResourceKey);
        RecoveryChecks.required("arguments", arguments);
        RecoveryChecks.required("sampling", sampling);
        RecoveryChecks.required("predicate", predicate);
        RecoveryField field = RecoveryField.find(capability, predicate.field())
                .orElseThrow(() -> RecoveryChecks.invalid("predicate.field"));
        switch (predicate) {
            case RecoveryPredicateV1.FieldEquals equals -> {
                if (field.numeric() || !field.textValues().contains(equals.value())) {
                    throw RecoveryChecks.invalid("predicate.value");
                }
            }
            case RecoveryPredicateV1.NumericCompare ignored -> {
                if (!field.numeric()) {
                    throw RecoveryChecks.invalid("predicate.field");
                }
            }
            case RecoveryPredicateV1.MonotonicTrend ignored -> {
                if (!field.numeric()) {
                    throw RecoveryChecks.invalid("predicate.field");
                }
                // 不能仅凭一个点宣称有趋势（06 §113）
                if (sampling.sampleCount() < 2) {
                    throw RecoveryChecks.invalid("sampling.sampleCount");
                }
            }
        }
    }
}
