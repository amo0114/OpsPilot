package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityDescriptorBuilder;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import io.github.ismoyuan.opspilot.application.faultlab.FaultLabApplicationService;
import io.github.ismoyuan.opspilot.application.faultlab.FaultScenario;
import io.github.ismoyuan.opspilot.application.faultlab.FaultScenarioCatalog;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultCommand;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultResult;
import io.github.ismoyuan.opspilot.application.faultlab.evaluation.FaultEvaluationService;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.infrastructure.ai.AiProtocolCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-091、09 §20～§21、ACC-INV-003：Ground Truth 只经 Fault Lab 与 Evaluation 代码路径读取，InvestigationContextBuilder 与 AI 请求
 * 永远读不到。两层证明：
 *
 * <ol>
 *   <li>结构：domain/application/infrastructure 中只有 faultlab 包（及注册 Schema 的 Codec）依赖 Fault Lab 类型；只有 Evaluation 端口的
 *       实现与使用者依赖 GroundTruthQuery；Mapper SQL 中只有 Fault Lab 的 Mapper 访问 fault_experiment，且读取 ground_truth_payload 的
 *       只有 Evaluation 专用语句。
 *   <li>行为：对三个场景经正式 Fault Lab 注入创建的真实 Incident（实验带 Ground Truth）开始调查，构造调查上下文并按 AI 协议序列化，
 *       请求中不出现任何答案标记；同时 Evaluation 能读到 Ground Truth（标记确实在库中）。
 * </ol>
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/demo")
@Testcontainers
@Import({
    FaultLabApplicationService.class,
    FaultScenarioCatalog.class,
    FaultEvaluationService.class,
    IncidentApplicationService.class,
    InvestigationContextBuilder.class,
    CapabilityDescriptorBuilder.class,
    CapabilityProviderResolver.class,
    CapabilityAccess.class,
    ClockConfiguration.class,
    GroundTruthIsolationTest.Injectors.class
})
class GroundTruthIsolationTest {

    /** 由 Fault Harness 提供的答案或其身份（09 §21）；序列化的调查请求中出现任何一个即泄漏。 */
    static final List<String> ANSWER_MARKERS = List.of(
            "redis-latency",
            "mysql-slow-query",
            "statistics-consumer-stop",
            "REDIS_NETWORK_LATENCY",
            "MYSQL_SLOW_QUERY_POOL_EXHAUSTION",
            "STATISTICS_CONSUMER_STOPPED",
            "groundtruth",
            "ground_truth",
            "fault-lab",
            "fault_lab",
            "faultlab",
            "fault injected",
            "experiment",
            "inject",
            "latencyms");

    /** 注册 fault-lab.ground-truth Schema 的 Codec（包私有）。 */
    static final String SCHEMA_REGISTRY =
            "io/github/ismoyuan/opspilot/infrastructure/schema/JacksonSchemaCodecRegistry";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @TestConfiguration
    static class Injectors {

        @Bean
        ScriptedFaultInjector s1() {
            return new ScriptedFaultInjector("redis-latency");
        }

        @Bean
        ScriptedFaultInjector s2() {
            return new ScriptedFaultInjector("mysql-slow-query");
        }

        @Bean
        ScriptedFaultInjector s3() {
            return new ScriptedFaultInjector("statistics-consumer-stop");
        }
    }

    @Autowired
    FaultLabApplicationService faultLab;

    @Autowired
    List<ScriptedFaultInjector> injectors;

    @Autowired
    FaultScenarioCatalog catalog;

    @Autowired
    FaultEvaluationService evaluation;

    @Autowired
    InvestigationContextBuilder contexts;

    @Autowired
    JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 行为

    @Test
    void theInvestigationContextOfAFaultLabIncidentHoldsNoAnswer() {
        AiProtocolCodec codec = new AiProtocolCodec();
        // S3 的 Ground Truth 在确认生效时补上被停止的容器（09 §63）；它同样不得进入调查请求
        String stoppedContainer = "fedcba9876543210".repeat(4);
        injectors.stream()
                .filter(injector -> injector.scenarioKey().equals("statistics-consumer-stop"))
                .forEach(injector -> injector.stoppedContainerId = stoppedContainer);
        // S1 的 Ground Truth 在确认生效时补上 Gate 实测（09 §33）；其字段同样不得进入调查请求
        injectors.stream()
                .filter(injector -> injector.scenarioKey().equals("redis-latency"))
                .forEach(injector -> injector.redisLatencyGate = new FaultGroundTruthV1.RedisLatencyGate(
                        FaultGroundTruthV1.SymptomBranch.BOTH, 600, 612, 9, 2_345, 0.0, 0.25));
        for (FaultScenario scenario : catalog.all()) {
            InjectFaultResult injected =
                    faultLab.inject(new InjectFaultCommand(scenario.scenarioKey(), "shortlink-platform", "demo-user"));
            FaultGroundTruthV1 groundTruth = evaluation.groundTruth(injected.experimentId());
            assertThat(groundTruth.cause()).isEqualTo(scenario.groundTruth().cause());
            assertThat(groundTruth.latencyMs()).isEqualTo(scenario.groundTruth().latencyMs());
            List<String> markers = new ArrayList<>(ANSWER_MARKERS);
            if (groundTruth.containerId() != null) {
                assertThat(groundTruth.containerId()).isEqualTo(stoppedContainer);
                markers.add(stoppedContainer);
            }
            if (groundTruth.redisLatencyGate() != null) {
                assertThat(groundTruth.redisLatencyGate().symptomBranch())
                        .isEqualTo(FaultGroundTruthV1.SymptomBranch.BOTH);
                markers.addAll(List.of("redislatencygate", "symptombranch", "pingmedianms", "faultp99ms", "2345"));
            }
            long incidentId = jdbc.queryForObject(
                    "SELECT id FROM incident WHERE incident_key = ?",
                    Long.class,
                    injected.incident().incidentKey().value());
            startInvestigation(incidentId);

            InvestigationStepRequest request =
                    contexts.build(incidentId, 1).orElseThrow().toRequest(1, "corr_isolation");
            String json = codec.encode(request);

            assertThat(request.incident().title()).isEqualTo(scenario.incidentTitle());
            String lower = json.toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                assertThat(lower)
                        .as(scenario.scenarioKey() + " leaks " + marker)
                        .doesNotContain(marker.toLowerCase(Locale.ROOT));
            }
            faultLab.reset(injected.experimentId());
        }
    }

    /** 等价于 Start 的事实（Incident INVESTIGATING、Investigation run 1）；调查服务本身不在本测试范围。 */
    private void startInvestigation(long incidentId) {
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING', lock_version = 1 WHERE id = ?", incidentId);
        jdbc.update(
                "INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                        + " current_run_started_at, max_capability_calls, max_duration_seconds,"
                        + " agent_step_timeout_seconds, max_consecutive_ai_failures, created_at, updated_at) VALUES"
                        + " (?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1, UTC_TIMESTAMP(3), 12, 480, 60, 3, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                incidentId);
    }

    // ---------------------------------------------------------------- 结构

    /**
     * Fault Lab 类型只被 faultlab 包使用；例外只有注册 Schema 的 Codec（能解码不等于会读取：读取 Ground Truth 的只有 GroundTruthQuery）。
     * 调查上下文、AI 客户端、Capability、产品查询都在这些模块中，因而都不能触及 Ground Truth。
     */
    @Test
    void nothingOutsideTheFaultLabDependsOnIt() {
        Map<String, byte[]> classes = classes();
        assertThat(classes)
                .containsKey(
                        "io/github/ismoyuan/opspilot/application/investigation/context/InvestigationContextBuilder");

        List<String> violations = new ArrayList<>();
        classes.forEach((name, bytes) -> {
            boolean faultLabClass = name.contains("/faultlab/");
            boolean registersSchema = name.equals(SCHEMA_REGISTRY);
            if (!faultLabClass && !registersSchema && contains(bytes, "/opspilot/application/faultlab/")) {
                violations.add(name);
            }
            if (!faultLabClass && contains(bytes, "ground_truth")) {
                violations.add(name + " (ground_truth)");
            }
        });
        assertThat(violations).isEmpty();

        assertThat(classes.entrySet().stream()
                        .filter(entry -> contains(entry.getValue(), "faultlab/evaluation/GroundTruthQuery"))
                        .map(Map.Entry::getKey)
                        .map(name -> name.substring(name.lastIndexOf('/') + 1))
                        .sorted())
                .containsExactly("FaultEvaluationService", "GroundTruthQuery", "MyBatisGroundTruthQuery");
    }

    /**
     * Demo 控制面（停止/启动容器）只被 Fault Lab 使用：DemoControlClient 只被 infrastructure/faultlab 引用，Docker 客户端的 stop/start 只被
     * DemoControlClient 调用（08 TASK-093；调查与执行仍只有 inspect 与 restart）。
     */
    @Test
    void onlyTheFaultLabControlsDemoContainers() {
        Map<String, byte[]> classes = classes();
        String control = "io/github/ismoyuan/opspilot/infrastructure/provider/DemoControlClient";
        assertThat(classes).containsKey(control);
        List<String> users = new ArrayList<>();
        classes.forEach((name, bytes) -> {
            if (!name.startsWith(control) && contains(bytes, control)) {
                users.add(name);
            }
        });
        assertThat(users)
                .isNotEmpty()
                .allMatch(name -> name.startsWith("io/github/ismoyuan/opspilot/infrastructure/faultlab/"));

        String engine = "io/github/ismoyuan/opspilot/infrastructure/provider/DockerEngineClient";
        List<String> engineUsers = new ArrayList<>();
        classes.forEach((name, bytes) -> {
            if (!name.startsWith(engine) && contains(bytes, engine)) {
                engineUsers.add(name);
            }
        });
        assertThat(engineUsers).contains(control);
        for (String user : engineUsers) {
            // 常量池中完整等于 stop / start 的名字（方法引用）；restart、inspect 不会命中
            boolean stops = hasUtf8Constant(classes.get(user), "stop");
            boolean starts = hasUtf8Constant(classes.get(user), "start");
            if (user.startsWith(control)) {
                assertThat(stops && starts).as(user).isTrue();
            } else {
                assertThat(stops || starts).as(user).isFalse();
            }
        }
    }

    /** Mapper SQL：只有 Fault Lab 的 Mapper 访问 fault_experiment；ground_truth_payload 只在插入、确认生效时的补充与 Evaluation 专用读取中出现。 */
    @Test
    void onlyTheEvaluationStatementReadsTheGroundTruth() throws Exception {
        Map<String, String> mappers = mapperXml();
        assertThat(mappers).isNotEmpty();
        mappers.forEach((path, xml) -> {
            if (!path.endsWith("faultlab/FaultExperimentMapper.xml")) {
                assertThat(xml).as(path).doesNotContain("fault_experiment").doesNotContain("ground_truth");
            }
        });
        String faultLab = mappers.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("faultlab/FaultExperimentMapper.xml"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        Matcher statements = Pattern.compile("<(select|insert|update|delete) id=\"(\\w+)\"(.*?)</\\1>", Pattern.DOTALL)
                .matcher(faultLab);
        List<String> reading = new ArrayList<>();
        List<String> writing = new ArrayList<>();
        while (statements.find()) {
            if (statements.group(3).contains("ground_truth_payload")) {
                (statements.group(1).equals("select") ? reading : writing).add(statements.group(2));
            }
        }
        assertThat(reading).containsExactly("selectGroundTruth");
        assertThat(writing).containsExactlyInAnyOrder("insertInjecting", "markActive");
    }

    // ---------------------------------------------------------------- 扫描工具

    /** domain、application、infrastructure 的全部主代码类（按内部名）。 */
    private static Map<String, byte[]> classes() {
        Map<String, byte[]> classes = new TreeMap<>();
        for (Class<?> anchor : List.of(Incident.class, FaultLabApplicationService.class, AiProtocolCodec.class)) {
            read(
                    anchor,
                    ".class",
                    (name, bytes) -> classes.put(name.substring(0, name.length() - ".class".length()), bytes));
        }
        return classes;
    }

    private static Map<String, String> mapperXml() {
        Map<String, String> xml = new TreeMap<>();
        read(
                AiProtocolCodec.class,
                "Mapper.xml",
                (name, bytes) -> xml.put(name, new String(bytes, StandardCharsets.UTF_8)));
        return xml;
    }

    private interface Entry {
        void accept(String name, byte[] bytes);
    }

    /** 读取锚点类所在的类路径根（目录或 jar）下以 suffix 结尾、属于本项目的条目。 */
    private static void read(Class<?> anchor, String suffix, Entry entry) {
        try {
            Path root = Path.of(
                    anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file :
                            files.filter(f -> f.toString().endsWith(suffix)).toList()) {
                        String name = root.relativize(file).toString().replace('\\', '/');
                        if (name.startsWith("io/github/ismoyuan/opspilot/")) {
                            entry.accept(name, Files.readAllBytes(file));
                        }
                    }
                }
            } else {
                try (JarFile jar = new JarFile(root.toFile())) {
                    for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                        JarEntry next = entries.nextElement();
                        if (next.getName().endsWith(suffix)
                                && next.getName().startsWith("io/github/ismoyuan/opspilot/")) {
                            try (InputStream in = jar.getInputStream(next)) {
                                entry.accept(next.getName(), in.readAllBytes());
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    /** 类文件常量池中是否有内容恰为 text 的 CONSTANT_Utf8（tag 1、u2 长度、字节）。 */
    private static boolean hasUtf8Constant(byte[] bytes, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        byte[] entry = new byte[value.length + 3];
        entry[0] = 1;
        entry[1] = (byte) (value.length >> 8);
        entry[2] = (byte) value.length;
        System.arraycopy(value, 0, entry, 3, value.length);
        return contains(bytes, new String(entry, StandardCharsets.ISO_8859_1));
    }
}
