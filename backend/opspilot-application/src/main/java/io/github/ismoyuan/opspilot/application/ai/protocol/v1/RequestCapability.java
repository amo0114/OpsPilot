package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;

/**
 * REQUEST_CAPABILITY 主 payload（05 §80），按 capabilityKey 判别，每个变体的 arguments 是对应的强类型（06 §21）；
 * 只有六种 OBSERVE 能力。资源归属、绑定、Descriptor 取值、窗口与预算由 Java 准入事务再校验（TASK-047/048）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "capabilityKey")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RequestCapability.MetricsQuery.class, name = "metrics.query"),
    @JsonSubTypes.Type(value = RequestCapability.LogsSearch.class, name = "logs.search"),
    @JsonSubTypes.Type(value = RequestCapability.DatabaseInspect.class, name = "database.inspect"),
    @JsonSubTypes.Type(value = RequestCapability.CacheInspect.class, name = "cache.inspect"),
    @JsonSubTypes.Type(value = RequestCapability.QueueInspect.class, name = "queue.inspect"),
    @JsonSubTypes.Type(value = RequestCapability.ServiceInspect.class, name = "service.inspect")
})
public sealed interface RequestCapability {

    /** purpose 上限（本批协议取值）。 */
    int PURPOSE_MAX = 500;

    long resourceId();

    CapabilityArguments arguments();

    String purpose();

    /** 由变体决定，线上以 capabilityKey 判别属性出现。 */
    @JsonIgnore
    CapabilityKey capabilityKey();

    record MetricsQuery(long resourceId, MetricsQueryArgumentsV1 arguments, String purpose)
            implements RequestCapability {

        public MetricsQuery {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.METRICS_QUERY;
        }
    }

    record LogsSearch(long resourceId, LogsSearchArgumentsV1 arguments, String purpose) implements RequestCapability {

        public LogsSearch {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.LOGS_SEARCH;
        }
    }

    record DatabaseInspect(long resourceId, DatabaseInspectArgumentsV1 arguments, String purpose)
            implements RequestCapability {

        public DatabaseInspect {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.DATABASE_INSPECT;
        }
    }

    record CacheInspect(long resourceId, CacheInspectArgumentsV1 arguments, String purpose)
            implements RequestCapability {

        public CacheInspect {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.CACHE_INSPECT;
        }
    }

    record QueueInspect(long resourceId, QueueInspectArgumentsV1 arguments, String purpose)
            implements RequestCapability {

        public QueueInspect {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.QUEUE_INSPECT;
        }
    }

    record ServiceInspect(long resourceId, ServiceInspectArgumentsV1 arguments, String purpose)
            implements RequestCapability {

        public ServiceInspect {
            common(resourceId, arguments, purpose);
        }

        @Override
        public CapabilityKey capabilityKey() {
            return CapabilityKey.SERVICE_INSPECT;
        }
    }

    /** 无参数能力也必须收到显式 {}：缺省或 null 都拒绝（08 TASK-028）。 */
    private static void common(long resourceId, CapabilityArguments arguments, String purpose) {
        ProtocolChecks.id("resourceId", resourceId);
        ProtocolChecks.required("arguments", arguments);
        ProtocolChecks.text("purpose", purpose, PURPOSE_MAX);
    }
}
