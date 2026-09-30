package io.github.ismoyuan.opspilot.application.recovery;

/**
 * 一个 Criterion 的采样规则（06 §113）：第一次样本在 Criterion 开始时准入，后续最早为上一实际样本完成时间加 intervalSeconds；
 * 相邻实际样本间隔超过 maxGapSeconds 时不能凭不连续数据宣布通过。
 *
 * <p>单样本：intervalSeconds 为 0、maxGapSeconds 为空；多样本：intervalSeconds ≥ 1 且 maxGapSeconds ≥ intervalSeconds
 * （Demo 默认 2 × intervalSeconds，04 §80）。
 */
public record RecoverySamplingV1(int sampleCount, int intervalSeconds, Integer maxGapSeconds) {

    public RecoverySamplingV1 {
        RecoveryChecks.positive("sampling.sampleCount", sampleCount);
        if (sampleCount == 1) {
            if (intervalSeconds != 0) {
                throw RecoveryChecks.invalid("sampling.intervalSeconds");
            }
            if (maxGapSeconds != null) {
                throw RecoveryChecks.invalid("sampling.maxGapSeconds");
            }
        } else {
            RecoveryChecks.positive("sampling.intervalSeconds", intervalSeconds);
            if (maxGapSeconds == null || maxGapSeconds < intervalSeconds) {
                throw RecoveryChecks.invalid("sampling.maxGapSeconds");
            }
        }
    }

    /** 两次准入之间至少等待的总时长，用于检查采样计划能否在 maxDurationSeconds 内完成。 */
    long minimumSpanSeconds() {
        return (long) (sampleCount - 1) * intervalSeconds;
    }
}
