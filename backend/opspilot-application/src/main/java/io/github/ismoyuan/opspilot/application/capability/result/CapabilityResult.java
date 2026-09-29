package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;

/**
 * 一次 OBSERVE 调用成功后的已结构化结果（06 §31 Typed Result、§118～§119），即 response_payload 的内容；由各 Provider（TASK-052～057）
 * 从真实返回构造，经 Sanitizer 后编码保存并交给 ObservationExtractor。构造器只接受自洽的真实值：缺失即为空，不以 0 代替。
 */
public sealed interface CapabilityResult
        permits MetricsQueryResultV1,
                LogsSearchResultV1,
                CacheInspectResultV1,
                DatabaseInspectResultV1,
                QueueInspectResultV1,
                ServiceInspectResultV1 {

    /** 该结果的 Schema（{capabilityKey}.result / 1）；必须与所执行能力的 Registry 定义一致。 */
    CapabilitySchema resultSchema();
}
