package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * AI 可见的能力描述（06 §22）：按 descriptorType 判别，key 与类型一一对应，并给出该资源实际允许的受控参数域。
 * 只由受信配置构造（TASK-045）；service.restart 不在此联合中，只出现在 Remediation allowedActions（BND-014）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "descriptorType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CapabilityDescriptor.MetricsQuery.class, name = "METRICS_QUERY"),
    @JsonSubTypes.Type(value = CapabilityDescriptor.LogsSearch.class, name = "LOGS_SEARCH"),
    @JsonSubTypes.Type(value = CapabilityDescriptor.DatabaseInspect.class, name = "DATABASE_INSPECT"),
    @JsonSubTypes.Type(value = CapabilityDescriptor.CacheInspect.class, name = "CACHE_INSPECT"),
    @JsonSubTypes.Type(value = CapabilityDescriptor.QueueInspect.class, name = "QUEUE_INSPECT"),
    @JsonSubTypes.Type(value = CapabilityDescriptor.ServiceInspect.class, name = "SERVICE_INSPECT")
})
public sealed interface CapabilityDescriptor {

    String key();

    long resourceId();

    String resourceKey();

    /** @param supportsPreviousWindowComparison 必须显式给出 */
    record MetricsQuery(
            String key,
            long resourceId,
            String resourceKey,
            List<String> metricKeys,
            List<WindowKey> windowKeys,
            Boolean supportsPreviousWindowComparison)
            implements CapabilityDescriptor {

        public static final String KEY = "metrics.query";

        public MetricsQuery {
            common(KEY, key, resourceId, resourceKey);
            metricKeys = ProtocolChecks.uniqueList("metricKeys", metricKeys, 1, Integer.MAX_VALUE);
            metricKeys.forEach(ProtocolChecks::metricKey);
            windowKeys = ProtocolChecks.uniqueList("windowKeys", windowKeys, 1, Integer.MAX_VALUE);
            ProtocolChecks.required("supportsPreviousWindowComparison", supportsPreviousWindowComparison);
        }
    }

    /** 关键字上限是冻结常量（06 §22、§50）。 */
    record LogsSearch(
            String key,
            long resourceId,
            String resourceKey,
            List<WindowKey> windowKeys,
            List<LogSeverity> severities,
            Integer maxKeywords,
            Integer maxKeywordLength)
            implements CapabilityDescriptor {

        public static final String KEY = "logs.search";

        public LogsSearch {
            common(KEY, key, resourceId, resourceKey);
            windowKeys = ProtocolChecks.uniqueList("windowKeys", windowKeys, 1, Integer.MAX_VALUE);
            severities = ProtocolChecks.uniqueList("severities", severities, 1, Integer.MAX_VALUE);
            ProtocolChecks.constant("maxKeywords", LogsSearchArgumentsV1.MAX_KEYWORDS, maxKeywords);
            ProtocolChecks.constant("maxKeywordLength", LogsSearchArgumentsV1.MAX_KEYWORD_LENGTH, maxKeywordLength);
        }
    }

    /** limit 范围是冻结常量（06 §72）。 */
    record DatabaseInspect(
            String key,
            long resourceId,
            String resourceKey,
            List<InspectionType> inspectionTypes,
            Integer limitMin,
            Integer limitMax)
            implements CapabilityDescriptor {

        public static final String KEY = "database.inspect";

        public DatabaseInspect {
            common(KEY, key, resourceId, resourceKey);
            inspectionTypes = ProtocolChecks.uniqueList("inspectionTypes", inspectionTypes, 1, Integer.MAX_VALUE);
            ProtocolChecks.constant("limitMin", DatabaseInspectArgumentsV1.LIMIT_MIN, limitMin);
            ProtocolChecks.constant("limitMax", DatabaseInspectArgumentsV1.LIMIT_MAX, limitMax);
        }
    }

    record CacheInspect(String key, long resourceId, String resourceKey) implements CapabilityDescriptor {

        public static final String KEY = "cache.inspect";

        public CacheInspect {
            common(KEY, key, resourceId, resourceKey);
        }
    }

    record QueueInspect(String key, long resourceId, String resourceKey) implements CapabilityDescriptor {

        public static final String KEY = "queue.inspect";

        public QueueInspect {
            common(KEY, key, resourceId, resourceKey);
        }
    }

    record ServiceInspect(String key, long resourceId, String resourceKey) implements CapabilityDescriptor {

        public static final String KEY = "service.inspect";

        public ServiceInspect {
            common(KEY, key, resourceId, resourceKey);
        }
    }

    private static void common(String expectedKey, String key, long resourceId, String resourceKey) {
        ProtocolChecks.constant("key", expectedKey, key);
        ProtocolChecks.id("resourceId", resourceId);
        ProtocolChecks.resourceKey(resourceKey);
    }
}
