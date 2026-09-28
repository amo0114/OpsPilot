package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Map;

/**
 * 资源在 Loki 中的日志流标签（06 §19）；AI 只提供 windowKey/severity/keywords，不提交 LogQL（06 §49）。
 *
 * @param labels 定位日志流的标签，至少一个
 */
public record LokiResourceBindingV1(Map<String, String> labels) implements ResourceSelector {

    public static final String SCHEMA_NAME = "loki.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    public LokiResourceBindingV1 {
        labels = BindingLabels.requireValid(labels);
    }
}
