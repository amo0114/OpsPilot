package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.capability.ObservationDraft;
import io.github.ismoyuan.opspilot.application.capability.extract.DatabaseStatusObservationV1.SlowQueryOverview;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedResult;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 确定性 Observation 提取（08 TASK-051、06 §29～§30、§44、§55、§65、§79、§89、§98）：同一结果总得到同一组 Observation，不调用 LLM。
 * 只接受已脱敏结果（{@link SanitizedResult}），载荷按 Observation Schema 编码，摘要（AI Context 只看摘要）由 {@link ObservationSummaries}
 * 只用结果中真实存在的值写出：不从单点写趋势，没有前窗口样本不写比较，没有基线不写“较正常升高”，单次队列采样不写“持续增长”。
 *
 * <p>一次调用可产生多条：logs.search 每个模式一条，database.inspect SLOW_QUERIES 一条汇总加每条语句一条；其余一条。
 * 来源 Invocation 的 Incident、调查/恢复、资源上下文由结果事务补齐（06 §27、CapabilityResultRecorder）。
 */
@Component
public class ObservationExtractor {

    private final SchemaCodecRegistry codecs;

    public ObservationExtractor(SchemaCodecRegistry codecs) {
        this.codecs = codecs;
    }

    /**
     * @param observedAt Provider 取得这些数据的真实时刻
     */
    public List<ObservationDraft> extract(SanitizedResult result, Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        return switch (result.value()) {
            case MetricsQueryResultV1 metrics -> List.of(metric(metrics, observedAt));
            case LogsSearchResultV1 logs -> logPatterns(logs, observedAt);
            case CacheInspectResultV1 cache -> List.of(cache(cache, observedAt));
            case DatabaseInspectResultV1 database -> database(database, observedAt);
            case QueueInspectResultV1 queue -> List.of(queue(queue, observedAt));
            case ServiceInspectResultV1 service -> List.of(service(service, observedAt));
        };
    }

    private ObservationDraft metric(MetricsQueryResultV1 result, Instant observedAt) {
        MetricObservationV1 payload = new MetricObservationV1(
                result.metricKey(),
                result.unit(),
                result.window(),
                result.sampleCount(),
                result.latest(),
                result.min(),
                result.max(),
                result.average(),
                result.previousWindow(),
                result.changePercent(),
                result.trend());
        return draft(
                ObservationKind.METRIC,
                MetricObservationV1.SCHEMA_NAME,
                MetricObservationV1.SCHEMA_VERSION,
                payload,
                ObservationSummaries.metric(payload),
                observedAt,
                result.window());
    }

    private List<ObservationDraft> logPatterns(LogsSearchResultV1 result, Instant observedAt) {
        if (result.patterns().isEmpty()) {
            LogPatternObservationV1 payload = new LogPatternObservationV1(
                    result.window(), null, null, 0, null, null, List.of(), result.totalMatches(), result.truncated());
            return List.of(logDraft(payload, observedAt));
        }
        List<ObservationDraft> drafts = new ArrayList<>();
        for (LogPattern pattern : result.patterns()) {
            drafts.add(logDraft(
                    new LogPatternObservationV1(
                            result.window(),
                            pattern.pattern(),
                            pattern.severity(),
                            pattern.count(),
                            pattern.firstSeen(),
                            pattern.lastSeen(),
                            pattern.samples(),
                            result.totalMatches(),
                            result.truncated()),
                    observedAt));
        }
        return List.copyOf(drafts);
    }

    private ObservationDraft logDraft(LogPatternObservationV1 payload, Instant observedAt) {
        return draft(
                ObservationKind.LOG_PATTERN,
                LogPatternObservationV1.SCHEMA_NAME,
                LogPatternObservationV1.SCHEMA_VERSION,
                payload,
                ObservationSummaries.logPattern(payload),
                observedAt,
                payload.window());
    }

    private ObservationDraft cache(CacheInspectResultV1 result, Instant observedAt) {
        CacheStatusObservationV1 payload = new CacheStatusObservationV1(
                result.reachable(),
                result.pingLatencyMs(),
                result.usedMemoryBytes(),
                result.maxMemoryBytes(),
                result.connectedClients(),
                result.blockedClients(),
                result.instantOpsPerSec(),
                result.keyspaceHits(),
                result.keyspaceMisses(),
                result.hitRate(),
                result.evictedKeys(),
                result.expiredKeys(),
                result.uptimeSeconds());
        return draft(
                ObservationKind.CACHE_STATUS,
                CacheStatusObservationV1.SCHEMA_NAME,
                CacheStatusObservationV1.SCHEMA_VERSION,
                payload,
                ObservationSummaries.cache(payload),
                observedAt,
                null);
    }

    private List<ObservationDraft> database(DatabaseInspectResultV1 result, Instant observedAt) {
        List<DatabaseStatusObservationV1> payloads = new ArrayList<>();
        switch (result.inspectionType()) {
            case SERVER_SUMMARY ->
                payloads.add(new DatabaseStatusObservationV1(
                        result.inspectionType(), result.serverSummary(), null, null, null, null));
            case CONNECTION_SUMMARY ->
                payloads.add(new DatabaseStatusObservationV1(
                        result.inspectionType(), null, result.connectionSummary(), null, null, null));
            case LOCK_WAITS ->
                payloads.add(new DatabaseStatusObservationV1(
                        result.inspectionType(), null, null, null, null, result.lockWaits()));
            case SLOW_QUERIES -> {
                payloads.add(new DatabaseStatusObservationV1(
                        result.inspectionType(), null, null, overview(result.slowQueries()), null, null));
                for (SlowQuery query : result.slowQueries()) {
                    payloads.add(
                            new DatabaseStatusObservationV1(result.inspectionType(), null, null, null, query, null));
                }
            }
        }
        return payloads.stream()
                .map(payload -> draft(
                        ObservationKind.DATABASE_STATUS,
                        DatabaseStatusObservationV1.SCHEMA_NAME,
                        DatabaseStatusObservationV1.SCHEMA_VERSION,
                        payload,
                        ObservationSummaries.database(payload),
                        observedAt,
                        null))
                .toList();
    }

    private static SlowQueryOverview overview(List<SlowQuery> queries) {
        Double maxAverage = queries.stream()
                .map(SlowQuery::averageLatencyMs)
                .max(Double::compare)
                .orElse(null);
        long executions = queries.stream().mapToLong(SlowQuery::executionCount).sum();
        return new SlowQueryOverview(queries.size(), maxAverage, executions);
    }

    private ObservationDraft queue(QueueInspectResultV1 result, Instant observedAt) {
        QueueStatusObservationV1 payload = new QueueStatusObservationV1(
                result.queueType(),
                result.streamLength(),
                result.lastGeneratedId(),
                result.lastGeneratedAt(),
                result.consumerGroups());
        return draft(
                ObservationKind.QUEUE_STATUS,
                QueueStatusObservationV1.SCHEMA_NAME,
                QueueStatusObservationV1.SCHEMA_VERSION,
                payload,
                ObservationSummaries.queue(payload),
                observedAt,
                null);
    }

    private ObservationDraft service(ServiceInspectResultV1 result, Instant observedAt) {
        ServiceStatusObservationV1 payload = new ServiceStatusObservationV1(
                result.runtimeState(),
                result.healthStatus(),
                result.startedAt(),
                result.restartCount(),
                result.image(),
                result.exitCode(),
                result.finishedAt());
        return draft(
                ObservationKind.SERVICE_STATUS,
                ServiceStatusObservationV1.SCHEMA_NAME,
                ServiceStatusObservationV1.SCHEMA_VERSION,
                payload,
                ObservationSummaries.service(payload),
                observedAt,
                null);
    }

    private ObservationDraft draft(
            ObservationKind kind,
            String schemaName,
            int schemaVersion,
            Object payload,
            String summary,
            Instant observedAt,
            TimeRange window) {
        return new ObservationDraft(
                kind,
                schemaName,
                schemaVersion,
                codecs.encode(schemaName, schemaVersion, payload),
                summary,
                observedAt,
                window == null ? null : window.start(),
                window == null ? null : window.end());
    }
}
