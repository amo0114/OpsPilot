package io.github.ismoyuan.opspilot.infrastructure.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.recovery.CriterionEvaluation;
import io.github.ismoyuan.opspilot.application.recovery.CriterionReason;
import io.github.ismoyuan.opspilot.application.recovery.CriterionResult;
import io.github.ismoyuan.opspilot.application.recovery.ProjectedValue;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryField;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryOutcome;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateEvaluator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateEvaluator.Check;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.ComparisonOperator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.TrendDirection;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryProjection;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySample;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 08 TASK-077：受控谓词与 required 三值矩阵（06 §113、§116，09 ACC-FINAL-09/11/12）。样本以 10 秒间隔排列，求值时刻紧随末样本，
 * maxSampleAge 120 秒；时效与间断用例单独调整时间。
 */
class RecoveryPredicateEvaluatorTest {

    static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    static final Duration MAX_AGE = Duration.ofSeconds(120);

    /** Verification 冻结的 deadline；除期限用例外都远在样本之后。 */
    static final Instant DEADLINE = T0.plusSeconds(3600);

    /** S3 B：lag 健康上限 20，4 样本、间隔 10 秒、maxGap 20 秒。 */
    static final RecoveryCriterionV1 LAG_TREND = queue(
            "stream-lag-decreasing",
            new RecoverySamplingV1(4, 10, 20),
            new RecoveryPredicateV1.MonotonicTrend("lag", TrendDirection.DECREASING, 20.0, true));

    /** S3 D：pendingCount <= 20，2 样本。 */
    static final RecoveryCriterionV1 PENDING = queue(
            "stream-pending-healthy",
            new RecoverySamplingV1(2, 5, 10),
            new RecoveryPredicateV1.NumericCompare("pendingCount", ComparisonOperator.LTE, 20.0));

    /** S3 A：runtimeState == RUNNING，2 样本。 */
    static final RecoveryCriterionV1 RUNNING = new RecoveryCriterionV1.ServiceInspect(
            "consumer-running",
            "消费者持续运行",
            "statistics-consumer",
            new ServiceInspectArgumentsV1(),
            new RecoverySamplingV1(2, 5, 10),
            new RecoveryPredicateV1.FieldEquals("runtimeState", "RUNNING"),
            true);

    // ---------------------------------------------------------------- 06 §113 S3 趋势表、ACC-FINAL-11

    @Test
    void theS3LagTableFromTheSpecification() {
        assertThat(lagTrend(0.0, 0.0, 0.0, 0.0)).as("0,0,0,0 已健康并保持").isEqualTo(CriterionResult.TRUE);
        assertThat(lagTrend(2.0, 0.0, 1.0, 0.0)).as("2,0,1,0 健康区间内波动").isEqualTo(CriterionResult.TRUE);
        assertThat(lagTrend(200.0, 210.0, 50.0, 10.0)).as("总体下降并进入健康区间").isEqualTo(CriterionResult.TRUE);
        assertThat(lagTrend(200.0, 150.0, 100.0, 50.0)).as("尚未达健康区间").isEqualTo(CriterionResult.FALSE);
        assertThat(lagTrend(50.0, 10.0, 30.0, 0.0)).as("入健康区后再次超界").isEqualTo(CriterionResult.FALSE);
        assertThat(lagTrend(200.0, null, 10.0, 0.0)).as("缺有效必要样本").isEqualTo(CriterionResult.UNKNOWN);
    }

    /** 反弹超界由有效样本即可决定，即使还有缺失；缺失且无反弹不能判 FALSE，也不能把 null 当 0。 */
    @Test
    void aReboundIsDecisiveButMissingValuesAreNeverZero() {
        assertThat(lagTrend(10.0, null, 40.0, 0.0)).isEqualTo(CriterionResult.FALSE);
        assertThat(lagTrend(200.0, 150.0, 100.0, null)).isEqualTo(CriterionResult.UNKNOWN);
        assertThat(evaluate(LAG_TREND, numbers(0.0, 0.0, 0.0)).reason())
                .as("只有 3 个样本")
                .isEqualTo(CriterionReason.INSUFFICIENT_SAMPLES);
        assertThat(evaluate(LAG_TREND, numbers(0.0, -1.0, 0.0, 0.0)).result())
                .as("负数不是有效积压")
                .isEqualTo(CriterionResult.UNKNOWN);
    }

    /** 无健康区间的通用趋势：非增且至少一次严格下降；一次反向即 FALSE；完整但持平为 FALSE；不齐为 UNKNOWN。 */
    @Test
    void theGenericTrendRequiresAStrictMovementAndNoReversal() {
        RecoveryCriterionV1 decreasing = queue(
                "lag-trend",
                new RecoverySamplingV1(3, 10, 20),
                new RecoveryPredicateV1.MonotonicTrend("lag", TrendDirection.DECREASING, null, null));
        RecoveryCriterionV1 increasing = queue(
                "lag-rise",
                new RecoverySamplingV1(3, 10, 20),
                new RecoveryPredicateV1.MonotonicTrend("lag", TrendDirection.INCREASING, null, null));

        assertThat(evaluate(decreasing, numbers(30.0, 30.0, 10.0)).result()).isEqualTo(CriterionResult.TRUE);
        assertThat(evaluate(decreasing, numbers(30.0, 40.0, null)).result()).isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(decreasing, numbers(5.0, 5.0, 5.0)).result()).isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(decreasing, numbers(30.0, null, 10.0)).result()).isEqualTo(CriterionResult.UNKNOWN);
        assertThat(evaluate(increasing, numbers(1.0, 2.0, 2.0)).result()).isEqualTo(CriterionResult.TRUE);
        assertThat(evaluate(increasing, numbers(2.0, 1.0, 3.0)).result()).isEqualTo(CriterionResult.FALSE);
    }

    /** requireFinalHealthy=false：完整序列未进入健康区间但总体下降也可通过；仍不能反弹。 */
    @Test
    void withoutAFinalHealthyRequirementAnOverallDecreaseIsEnough() {
        RecoveryCriterionV1 lenient = queue(
                "lag-lenient",
                new RecoverySamplingV1(4, 10, 20),
                new RecoveryPredicateV1.MonotonicTrend("lag", TrendDirection.DECREASING, 20.0, false));

        assertThat(evaluate(lenient, numbers(200.0, 150.0, 100.0, 50.0)).result())
                .isEqualTo(CriterionResult.TRUE);
        assertThat(evaluate(lenient, numbers(200.0, 250.0, 210.0, 200.0)).result())
                .isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(lenient, numbers(50.0, 10.0, 30.0, 0.0)).result()).isEqualTo(CriterionResult.FALSE);
    }

    // ---------------------------------------------------------------- FIELD_EQUALS / NUMERIC_COMPARE

    /** 全部有效样本满足才 TRUE；任一有效样本违反即 FALSE（即使另一个失败）；没有违反但有缺失为 UNKNOWN。 */
    @Test
    void everyUsableSampleMustSatisfyAndOneViolationDecides() {
        assertThat(evaluate(RUNNING, texts("RUNNING", "RUNNING")).result()).isEqualTo(CriterionResult.TRUE);
        assertThat(evaluate(RUNNING, texts("RUNNING", "STOPPED")).result()).isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(RUNNING, List.of(failed(1), text(2, "STOPPED"))).result())
                .isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(RUNNING, List.of(text(1, "RUNNING"), failed(2))))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.SAMPLE_FAILED));
        assertThat(evaluate(RUNNING, List.of(text(1, "RUNNING"), running(2))).reason())
                .isEqualTo(CriterionReason.SAMPLE_FAILED);
        assertThat(evaluate(RUNNING, List.of(unknown(1, ProjectedValue.Unknown.STATE_UNKNOWN), text(2, "RUNNING"))))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.VALUE_UNKNOWN));
        assertThat(evaluate(PENDING, numbers(0.0, 20.0)).result()).isEqualTo(CriterionResult.TRUE);
        assertThat(evaluate(PENDING, numbers(0.0, 21.0)).result()).isEqualTo(CriterionResult.FALSE);
        assertThat(evaluate(PENDING, numbers(null, 3.0)).result()).isEqualTo(CriterionResult.UNKNOWN);
    }

    /** 四种比较运算。 */
    @Test
    void comparisonOperators() {
        for (Object[] row : new Object[][] {
            {ComparisonOperator.LT, 20.0, CriterionResult.FALSE},
            {ComparisonOperator.LT, 19.0, CriterionResult.TRUE},
            {ComparisonOperator.LTE, 20.0, CriterionResult.TRUE},
            {ComparisonOperator.GT, 20.0, CriterionResult.FALSE},
            {ComparisonOperator.GT, 21.0, CriterionResult.TRUE},
            {ComparisonOperator.GTE, 20.0, CriterionResult.TRUE},
            {ComparisonOperator.GTE, 19.5, CriterionResult.FALSE}
        }) {
            RecoveryCriterionV1 single = queue(
                    "single",
                    new RecoverySamplingV1(1, 0, null),
                    new RecoveryPredicateV1.NumericCompare("lag", (ComparisonOperator) row[0], 20.0));
            assertThat(evaluate(single, numbers((Double) row[1])).result())
                    .as("%s %s", row[0], row[1])
                    .isEqualTo(row[2]);
        }
    }

    // ---------------------------------------------------------------- 时效与间断（ACC-FINAL-10 的求值部分）

    /** 过期样本既不能支持 TRUE 也不能支持 FALSE：过期的违反样本不决定 FALSE。 */
    @Test
    void expiredSamplesSupportNeitherTrueNorFalse() {
        Instant late = T0.plusSeconds(10 + 121);
        List<RecoverySample> stoppedButExpired = List.of(text(1, "STOPPED"), text(2, "RUNNING"));

        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, stoppedButExpired, MAX_AGE, DEADLINE, late))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.SAMPLE_EXPIRED));
        assertThat(RecoveryPredicateEvaluator.evaluate(
                                RUNNING, stoppedButExpired, MAX_AGE, DEADLINE, T0.plusSeconds(10))
                        .result())
                .isEqualTo(CriterionResult.FALSE);
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, texts("RUNNING", "RUNNING"), MAX_AGE, DEADLINE, late)
                        .result())
                .isEqualTo(CriterionResult.UNKNOWN);
    }

    /**
     * 冻结 deadline 之后才取得的样本（04 §80、B28-R1）既不能支持 TRUE 也不能支持 FALSE：期限后的违反不决定 FALSE，期限后的满足
     * 不使序列完整；期限之前（含恰好等于）的样本照常有效。
     */
    @Test
    void samplesTakenAfterTheFrozenDeadlineSupportNeitherTrueNorFalse() {
        Instant deadline = T0.plusSeconds(5);
        Instant now = T0.plusSeconds(11);

        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, texts("RUNNING", "STOPPED"), MAX_AGE, deadline, now))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.SAMPLE_AFTER_DEADLINE));
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, texts("RUNNING", "RUNNING"), MAX_AGE, deadline, now))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.SAMPLE_AFTER_DEADLINE));
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, texts("STOPPED", "RUNNING"), MAX_AGE, deadline, now)
                        .result())
                .as("期限内的违反仍决定 FALSE")
                .isEqualTo(CriterionResult.FALSE);
        assertThat(RecoveryPredicateEvaluator.evaluate(
                                RUNNING, texts("RUNNING", "RUNNING"), MAX_AGE, T0.plusSeconds(10), now)
                        .result())
                .as("恰好在 deadline 取得仍有效")
                .isEqualTo(CriterionResult.TRUE);
    }

    /** 相邻实际样本间隔超过 maxGapSeconds：不能宣布通过，但有效违反仍是 FALSE。 */
    @Test
    void discontinuousSeriesCannotPassButCanStillFail() {
        List<RecoverySample> gapped = List.of(
                sample(1, T0, new ProjectedValue.Text("RUNNING")),
                sample(2, T0.plusSeconds(11), new ProjectedValue.Text("RUNNING")));
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, gapped, MAX_AGE, DEADLINE, T0.plusSeconds(12)))
                .isEqualTo(CriterionEvaluation.unknown(CriterionReason.SAMPLE_GAP_EXCEEDED));
        List<RecoverySample> gappedStop = List.of(
                sample(1, T0, new ProjectedValue.Text("RUNNING")),
                sample(2, T0.plusSeconds(11), new ProjectedValue.Text("STOPPED")));
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, gappedStop, MAX_AGE, DEADLINE, T0.plusSeconds(12))
                        .result())
                .isEqualTo(CriterionResult.FALSE);
        List<RecoverySample> exact = List.of(
                sample(1, T0, new ProjectedValue.Text("RUNNING")),
                sample(2, T0.plusSeconds(10), new ProjectedValue.Text("RUNNING")));
        assertThat(RecoveryPredicateEvaluator.evaluate(RUNNING, exact, MAX_AGE, DEADLINE, T0.plusSeconds(12))
                        .result())
                .as("恰好等于 maxGap 仍连续")
                .isEqualTo(CriterionResult.TRUE);
    }

    // ---------------------------------------------------------------- required 三值矩阵：ACC-FINAL-09、ACC-FINAL-12

    @Test
    void theRequiredMatrixPrefersFailedThenInconclusive() {
        assertThat(overall(req(CriterionResult.TRUE), req(CriterionResult.UNKNOWN)))
                .as("TRUE+UNKNOWN")
                .isEqualTo(RecoveryOutcome.INCONCLUSIVE);
        assertThat(overall(req(CriterionResult.FALSE), req(CriterionResult.UNKNOWN)))
                .as("FALSE+UNKNOWN")
                .isEqualTo(RecoveryOutcome.FAILED);
        assertThat(overall(req(CriterionResult.UNKNOWN), req(CriterionResult.FALSE)))
                .as("UNKNOWN 在前也不覆盖 FALSE")
                .isEqualTo(RecoveryOutcome.FAILED);
        assertThat(overall(req(CriterionResult.TRUE), req(CriterionResult.TRUE)))
                .as("全部 TRUE")
                .isEqualTo(RecoveryOutcome.PASSED);
        assertThat(overall(req(CriterionResult.TRUE), new Check(false, CriterionResult.FALSE)))
                .as("可选项不影响结果")
                .isEqualTo(RecoveryOutcome.PASSED);
        assertThatThrownBy(() -> overall(new Check(false, CriterionResult.TRUE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** ACC-FINAL-12：lag 降至 0 但 pending 超过健康范围，不得 PASSED；ACC-FINAL-11：lag 0,0,0,0 通过仍须其他 required 成立。 */
    @Test
    void aDrainedLagDoesNotPassWhilePendingIsUnhealthy() {
        CriterionResult lag = lagTrend(200.0, 100.0, 40.0, 0.0);
        CriterionResult pending = evaluate(PENDING, numbers(160.0, 200.0)).result();
        assertThat(lag).isEqualTo(CriterionResult.TRUE);
        assertThat(pending).isEqualTo(CriterionResult.FALSE);
        assertThat(overall(req(lag), req(pending))).isEqualTo(RecoveryOutcome.FAILED);

        CriterionResult healthyLag = lagTrend(0.0, 0.0, 0.0, 0.0);
        assertThat(overall(
                        req(healthyLag),
                        req(evaluate(RUNNING, List.of(failed(1), failed(2))).result())))
                .isEqualTo(RecoveryOutcome.INCONCLUSIVE);
        assertThat(overall(
                        req(healthyLag),
                        req(evaluate(RUNNING, texts("RUNNING", "RUNNING")).result())))
                .isEqualTo(RecoveryOutcome.PASSED);
    }

    // ---------------------------------------------------------------- 注册投影

    /** 只取快照冻结的组；组缺失、lag 为空、状态 UNKNOWN、latest 为空都是 UNKNOWN，不回退到第一组，不补 0。 */
    @Test
    void projectionsReadOnlyRegisteredFieldsOfTheFrozenGroup() {
        QueueInspectResultV1 queue = new QueueInspectResultV1(
                QueueInspectResultV1.QueueType.REDIS_STREAM,
                100,
                null,
                null,
                List.of(
                        new QueueInspectResultV1.ConsumerGroup("other-group", 1, 99, 99L, null, null),
                        new QueueInspectResultV1.ConsumerGroup("stats-consumer-group", 1, 3, null, null, null)));

        assertThat(RecoveryProjection.project(queue, RecoveryField.PENDING_COUNT, "stats-consumer-group"))
                .isEqualTo(new ProjectedValue.Number(3));
        assertThat(RecoveryProjection.project(queue, RecoveryField.LAG, "stats-consumer-group"))
                .isEqualTo(new ProjectedValue.Unknown(ProjectedValue.Unknown.FIELD_NULL));
        assertThat(RecoveryProjection.project(queue, RecoveryField.LAG, "missing-group"))
                .isEqualTo(new ProjectedValue.Unknown(ProjectedValue.Unknown.GROUP_MISSING));
        assertThat(RecoveryProjection.project(
                        new ServiceInspectResultV1(
                                RuntimeState.UNKNOWN, HealthStatus.HEALTHY, null, 0, null, null, null),
                        RecoveryField.RUNTIME_STATE,
                        null))
                .isEqualTo(new ProjectedValue.Unknown(ProjectedValue.Unknown.STATE_UNKNOWN));
        assertThat(RecoveryProjection.project(
                        new ServiceInspectResultV1(
                                RuntimeState.RUNNING, HealthStatus.HEALTHY, null, 0, null, null, null),
                        RecoveryField.HEALTH_STATUS,
                        null))
                .isEqualTo(new ProjectedValue.Text("HEALTHY"));
        assertThat(RecoveryProjection.project(queue, RecoveryField.RUNTIME_STATE, null))
                .isEqualTo(new ProjectedValue.Unknown(ProjectedValue.Unknown.RESULT_MISMATCH));
        assertThat(RecoveryProjection.resultType(RecoveryField.LATEST.capability()))
                .isEqualTo(MetricsQueryResultV1.class);
    }

    // ---------------------------------------------------------------- helpers

    private static RecoveryCriterionV1 queue(String key, RecoverySamplingV1 sampling, RecoveryPredicateV1 predicate) {
        return new RecoveryCriterionV1.QueueInspect(
                key, "检查 " + key, "statistics-stream", new QueueInspectArgumentsV1(), sampling, predicate, true);
    }

    private static CriterionResult lagTrend(Double... values) {
        return evaluate(LAG_TREND, numbers(values)).result();
    }

    /** 以末样本之后 1 秒求值。 */
    private static CriterionEvaluation evaluate(RecoveryCriterionV1 criterion, List<RecoverySample> samples) {
        Instant last = samples.stream()
                .map(RecoverySample::sampledAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(T0);
        return RecoveryPredicateEvaluator.evaluate(criterion, samples, MAX_AGE, DEADLINE, last.plusSeconds(1));
    }

    private static RecoveryOutcome overall(Check... checks) {
        return RecoveryPredicateEvaluator.overall(List.of(checks));
    }

    private static Check req(CriterionResult result) {
        return new Check(true, result);
    }

    /** null 表示该样本成功但 lag 为空。 */
    private static List<RecoverySample> numbers(Double... values) {
        List<RecoverySample> samples = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            samples.add(
                    values[i] == null
                            ? unknown(i + 1, ProjectedValue.Unknown.FIELD_NULL)
                            : sample(i + 1, at(i + 1), new ProjectedValue.Number(values[i])));
        }
        return samples;
    }

    private static List<RecoverySample> texts(String... values) {
        List<RecoverySample> samples = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            samples.add(text(i + 1, values[i]));
        }
        return samples;
    }

    private static RecoverySample text(int index, String value) {
        return sample(index, at(index), new ProjectedValue.Text(value));
    }

    private static RecoverySample unknown(int index, String cause) {
        return sample(index, at(index), new ProjectedValue.Unknown(cause));
    }

    private static RecoverySample failed(int index) {
        return new RecoverySample(index, index, RecoverySample.Status.FAILED, null, null);
    }

    private static RecoverySample running(int index) {
        return new RecoverySample(index, index, RecoverySample.Status.RUNNING, null, null);
    }

    private static RecoverySample sample(int index, Instant at, ProjectedValue value) {
        return new RecoverySample(index, index, RecoverySample.Status.SUCCEEDED, at, value);
    }

    /** 第 n 个样本的时间：10 秒间隔，与 maxGap 相容。 */
    private static Instant at(int index) {
        return T0.plusSeconds(10L * (index - 1));
    }
}
