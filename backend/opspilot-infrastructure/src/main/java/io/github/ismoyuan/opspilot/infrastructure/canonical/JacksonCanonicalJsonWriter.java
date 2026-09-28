package io.github.ismoyuan.opspilot.infrastructure.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.ismoyuan.opspilot.application.canonical.CanonicalJsonWriter;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link CanonicalJsonWriter} 的唯一实现：先按与 AI 协议编解码一致的规则（null 字段省略、空记录为 {}）转成 JSON 树，再递归按键排序、
 * 去掉 null 值字段后紧凑输出。线程安全。
 */
@Component
class JacksonCanonicalJsonWriter implements CanonicalJsonWriter {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .changeDefaultPropertyInclusion(
                    value -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    @Override
    public String write(Object value) {
        return mapper.writeValueAsString(normalize(mapper.valueToTree(value)));
    }

    @Override
    public String canonicalize(String json) {
        JsonNode tree;
        try {
            tree = mapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("not a JSON document");
        }
        return mapper.writeValueAsString(normalize(tree));
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!property.getValue().isNull()) {
                    sorted.put(property.getKey(), normalize(property.getValue()));
                }
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(normalize(element)));
            return result;
        }
        return node;
    }
}
