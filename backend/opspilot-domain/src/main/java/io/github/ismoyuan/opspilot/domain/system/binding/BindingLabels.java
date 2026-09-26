package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Prometheus 与 Loki 共用的标签选择器校验；标签名遵循两者一致的数据模型，且不允许保留的 __ 前缀。 */
final class BindingLabels {

    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private BindingLabels() {}

    /** 至少一个标签；异常文本只含字段名，不回显配置值。 */
    static Map<String, String> requireValid(Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels");
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("labels must not be empty");
        }
        labels.forEach((name, value) -> {
            if (name == null || !LABEL_NAME.matcher(name).matches() || name.startsWith("__")) {
                throw new IllegalArgumentException("labels contains an invalid label name");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("labels contains a blank label value");
            }
        });
        return Map.copyOf(labels);
    }
}
