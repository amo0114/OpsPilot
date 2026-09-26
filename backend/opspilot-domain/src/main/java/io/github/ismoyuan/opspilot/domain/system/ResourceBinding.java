package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * 某个资源在某个数据源中的定位方式，表达 ManagedResource 与 DataSourceConnection 的多对多关系（03 §10、04 §10）。
 * 选择器由配置提供，不由 AI 生成（06 §19）。
 *
 * @param selectorPayload 未解码的 JSON 对象文本，只能按 selectorSchema 经 Codec 解释
 */
public record ResourceBinding(
        long id,
        long managedResourceId,
        long dataSourceConnectionId,
        SelectorSchema selectorSchema,
        String selectorPayload) {

    public ResourceBinding {
        Objects.requireNonNull(selectorSchema, "selectorSchema");
        Objects.requireNonNull(selectorPayload, "selectorPayload");
    }
}
