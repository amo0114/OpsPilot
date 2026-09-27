package io.github.ismoyuan.opspilot.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationIntentType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java 端协议合同测试（08 TASK-032、07 §84、§110）：与 ai-runtime 的 pytest 读取同一组 contracts/ai-runtime/v1/fixtures。
 * valid 必须反序列化、校验并序列化回等价 JSON（可选字段缺省与 null 等价）；invalid 与 invalid-model-only 必须以 AI_OUTPUT_INVALID 拒绝。
 * Schema 中的枚举与 Java 枚举逐一比对，防止两边漂移。
 */
class AiProtocolContractTest {

    private static final Path CONTRACT = locateContract();
    private static final Map<String, Class<?>> MESSAGES = Map.of(
            "investigation-step-request", InvestigationStepRequest.class,
            "investigation-step-response", InvestigationStepResponse.class,
            "remediation-draft-request", RemediationDraftRequest.class,
            "remediation-draft-response", RemediationDraftResponse.class);

    private final AiProtocolCodec codec = new AiProtocolCodec();
    private final JsonMapper plain = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> validFixturesRoundTrip() {
        return fixtures("valid")
                .map(fixture -> DynamicTest.dynamicTest(fixture.toString(), () -> {
                    String json = read(fixture);
                    Object decoded = codec.decode(json, messageType(fixture));
                    // 可选字段缺省与 null 等价，序列化时不输出
                    assertThat(plain.readTree(codec.encode(decoded))).isEqualTo(withoutNulls(plain.readTree(json)));
                }));
    }

    @TestFactory
    Stream<DynamicTest> invalidFixturesAreRejected() {
        return Stream.concat(fixtures("invalid"), fixtures("invalid-model-only"))
                .map(fixture -> DynamicTest.dynamicTest(
                        fixture.toString(),
                        () -> assertThatThrownBy(() -> codec.decode(read(fixture), messageType(fixture)))
                                .isInstanceOfSatisfying(
                                        OpsPilotException.class,
                                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_OUTPUT_INVALID))));
    }

    /** 每份 Schema 都有正例与负例，避免空目录让合同测试形同虚设。 */
    @Test
    void everySchemaHasPositiveAndNegativeFixtures() {
        for (String name : MESSAGES.keySet()) {
            assertThat(Files.isRegularFile(CONTRACT.resolve(name + ".schema.json")))
                    .as(name)
                    .isTrue();
            assertThat(list(CONTRACT.resolve("fixtures").resolve(name).resolve("valid")))
                    .as(name)
                    .isNotEmpty();
            assertThat(list(CONTRACT.resolve("fixtures").resolve(name).resolve("invalid")))
                    .as(name)
                    .isNotEmpty();
        }
    }

    @Test
    void schemaEnumerationsMatchJavaEnums() {
        JsonNode request = schema("investigation-step-request");
        JsonNode response = schema("investigation-step-response");
        assertEnum(
                request.at("/properties/affectedResources/items/properties/resourceType/enum"), ResourceType.values());
        assertEnum(request.at("/properties/hypotheses/items/properties/status/enum"), HypothesisStatus.values());
        assertEnum(request.at("/properties/observations/items/properties/kind/enum"), ObservationKind.values());
        assertEnum(request.at("/properties/evidence/items/properties/relation/enum"), EvidenceRelation.values());
        assertEnum(request.at("/$defs/windowKey/enum"), WindowKey.values());
        assertEnum(request.at("/$defs/logSeverity/enum"), LogSeverity.values());
        assertEnum(request.at("/$defs/inspectionType/enum"), InspectionType.values());
        assertEnum(response.at("/$defs/windowKey/enum"), WindowKey.values());
        assertEnum(response.at("/$defs/logSeverity/enum"), LogSeverity.values());
        assertEnum(
                response.at("/$defs/diagnosisDraft/properties/conclusionType/enum"), DiagnosisConclusionType.values());
        assertThat(texts(response.at("/$defs/targetStatus/enum")))
                .containsExactly("SUPPORTED", "INSUFFICIENT_EVIDENCE", "REFUTED");
        List<String> intents = new ArrayList<>();
        response.path("oneOf")
                .forEach(variant ->
                        intents.add(variant.at("/properties/intentType/const").asString()));
        assertThat(intents)
                .containsExactly(Arrays.stream(InvestigationIntentType.values())
                        .map(Enum::name)
                        .toArray(String[]::new));
    }

    private static JsonNode withoutNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = object.objectNode();
            object.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) {
                    copy.set(entry.getKey(), withoutNulls(entry.getValue()));
                }
            });
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = array.arrayNode();
            array.forEach(item -> copy.add(withoutNulls(item)));
            return copy;
        }
        return node;
    }

    private void assertEnum(JsonNode schemaEnum, Enum<?>[] values) {
        assertThat(texts(schemaEnum))
                .containsExactly(Arrays.stream(values).map(Enum::name).toArray(String[]::new));
    }

    private static List<String> texts(JsonNode array) {
        assertThat(array.isArray()).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private JsonNode schema(String name) {
        return plain.readTree(read(CONTRACT.resolve(name + ".schema.json")));
    }

    private static Class<?> messageType(Path fixture) {
        return MESSAGES.get(fixture.getParent().getParent().getFileName().toString());
    }

    private static Stream<Path> fixtures(String kind) {
        return MESSAGES.keySet().stream()
                .sorted()
                .flatMap(name -> list(CONTRACT.resolve("fixtures").resolve(name).resolve(kind)).stream());
    }

    private static List<Path> list(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 自模块目录向上查找仓库根下的 contracts/ai-runtime/v1。 */
    private static Path locateContract() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts").resolve("ai-runtime").resolve("v1");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "contracts/ai-runtime/v1 not found above " + Path.of("").toAbsolutePath());
    }
}
