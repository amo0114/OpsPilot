package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ConnectionSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWaits;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ServerSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import java.util.Objects;

/**
 * database-status.observation / 1（06 §79、§117）：恰有一节。SLOW_QUERIES 产生一条 slowQueryOverview 汇总与每条语句一条 slowQuery，
 * 只含 Provider 返回的当前规范化统计，不含基线或前后比较。
 */
public record DatabaseStatusObservationV1(
        InspectionType inspectionType,
        ServerSummary serverSummary,
        ConnectionSummary connectionSummary,
        SlowQueryOverview slowQueryOverview,
        SlowQuery slowQuery,
        LockWaits lockWaits) {

    public static final String SCHEMA_NAME = "database-status.observation";
    public static final int SCHEMA_VERSION = 1;

    /**
     * @param maxAverageLatencyMs 返回语句中最高的平均耗时；没有语句时为空
     */
    public record SlowQueryOverview(long queryCount, Double maxAverageLatencyMs, long totalExecutionCount) {}

    public DatabaseStatusObservationV1 {
        Objects.requireNonNull(inspectionType, "inspectionType");
        int sections = (serverSummary == null ? 0 : 1)
                + (connectionSummary == null ? 0 : 1)
                + (slowQueryOverview == null ? 0 : 1)
                + (slowQuery == null ? 0 : 1)
                + (lockWaits == null ? 0 : 1);
        if (sections != 1) {
            throw new IllegalArgumentException("exactly one section must be present");
        }
    }
}
