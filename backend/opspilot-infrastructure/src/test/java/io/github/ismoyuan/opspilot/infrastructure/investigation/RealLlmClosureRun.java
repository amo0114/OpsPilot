package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAdmissionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityDescriptorBuilder;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.CapabilityResultRecorder;
import io.github.ismoyuan.opspilot.application.capability.DuplicateGuard;
import io.github.ismoyuan.opspilot.application.capability.ObserveResultPipeline;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderCapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.IntentDispatcher;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationCapabilityExecutor;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationOrchestrator;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationTerminator;
import io.github.ismoyuan.opspilot.application.investigation.recovery.InvestigationInterruptionRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmissionService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContext;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.ValidatedRemediationProposal;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-058 完成证据：真实 AI 决策 → 真实 OBSERVE → 真实 Observation → 再次 AI 决策的闭合运行。不是常规测试：类名不符合 Surefire
 * 默认模式，`verify` 不运行也不计跳过；需要先启动 openai-compatible 模式的 ai-runtime，再显式运行：
 *
 * <pre>
 * ./mvnw -B test -pl opspilot-infrastructure -am -Dtest=RealLlmClosureRun -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dopspilot.closure.ai-runtime-url=http://127.0.0.1:18000 -Dopspilot.closure.ai-runtime-token=... \
 *     -Dopspilot.closure.model=...
 * </pre>
 *
 * <p>Java 进程只持有内部 Token，LLM Key 只在 ai-runtime 进程中。链路全部为生产实现：HttpAiRuntimeClient、调查编排、准入/结果事务、
 * ProviderCapabilityInvoker 与真实 Redis Stream（消费者停止后仍有写入）、真实已退出容器（退出码 137）的只读 Provider；只把
 * AiDecisionPort 包一层记录每步收到的上下文。运行记录写入 target/real-llm-closure.txt。
 */
@SpringBootTest
@Testcontainers
@Import({
    InvestigationOrchestrator.class,
    InvestigationTerminator.class,
    InvestigationInterruptionRecorder.class,
    IntentDispatcher.class,
    InvestigationCapabilityExecutor.class,
    CapabilityExecutionService.class,
    CapabilityAdmissionService.class,
    DuplicateGuard.class,
    CapabilityResultRecorder.class,
    Sanitizer.class,
    ObservationExtractor.class,
    ObserveResultPipeline.class,
    ProviderCapabilityInvoker.class,
    InvestigationContextBuilder.class,
    CapabilityDescriptorBuilder.class,
    CapabilityProviderResolver.class,
    CapabilityAccess.class,
    StepAdmissionService.class,
    AgentStepRecorder.class,
    HypothesisApplicationService.class,
    HypothesisStatusRecorder.class,
    EvidenceApplicationService.class,
    DiagnosisApplicationService.class,
    InvestigationApplicationService.class,
    IncidentApplicationService.class,
    ClockConfiguration.class,
    RemediationDraftContextBuilder.class,
    RemediationActions.class,
    RemediationProposalValidator.class,
    RealLlmClosureRun.RecordingAi.class
})
class RealLlmClosureRun {

    static final String AI_URL = required("opspilot.closure.ai-runtime-url");
    static final String AI_TOKEN = required("opspilot.closure.ai-runtime-token");
    static final String MODEL = required("opspilot.closure.model");
    static final String CONSUMER = "opspilot-closure-" + ProcessHandle.current().pid() + "-statistics-consumer";
    static final Path RAW_RESULTS = createTempDirectory();
    static final List<InvestigationStepRequest> REQUESTS = new CopyOnWriteArrayList<>();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

    /** 生产 AiDecisionPort（HttpAiRuntimeClient）外只加记录，不改请求与结果。 */
    @TestConfiguration
    static class RecordingAi {
        @Bean
        @Primary
        AiDecisionPort recordingAiDecisionPort(@Qualifier("aiDecisionPort") AiDecisionPort real) {
            return new AiDecisionPort() {
                @Override
                public InvestigationStepDecision decideInvestigationStep(
                        InvestigationStepRequest request, Duration maxWait) {
                    REQUESTS.add(request);
                    return real.decideInvestigationStep(request, maxWait);
                }

                @Override
                public RemediationDraftResponse draftRemediation(RemediationDraftRequest request) {
                    return real.draftRemediation(request);
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("opspilot.capability.raw-result-directory", RAW_RESULTS::toString);
        registry.add("opspilot.ai-runtime.base-url", () -> AI_URL);
        registry.add("opspilot.ai-runtime.token", () -> AI_TOKEN);
    }

    /** 统计消费者读取并确认 10 条后停止（容器以 137 退出），生产者继续写入 45 条。 */
    @BeforeAll
    static void prepareTargets() throws Exception {
        redis("XGROUP", "CREATE", "shortlink:stats", "stats-consumer-group", "$", "MKSTREAM");
        List<String> delivered = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            delivered.add(redis("XADD", "shortlink:stats", "*", "shortCode", "s" + i));
        }
        redis(
                "XREADGROUP",
                "GROUP",
                "stats-consumer-group",
                "consumer-1",
                "COUNT",
                "10",
                "STREAMS",
                "shortlink:stats",
                ">");
        for (String id : delivered) {
            redis("XACK", "shortlink:stats", "stats-consumer-group", id);
        }
        for (int i = 10; i < 55; i++) {
            redis("XADD", "shortlink:stats", "*", "shortCode", "s" + i);
        }
        docker("run", "--name", CONSUMER, "redis:7.4.5", "sh", "-c", "exit 137");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        docker("rm", "-f", CONSUMER);
        FileSystemUtils.deleteRecursively(RAW_RESULTS);
    }

    @Autowired
    InvestigationOrchestrator orchestrator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AiDecisionPort ai;

    @Autowired
    RemediationDraftContextBuilder remediationContexts;

    @Autowired
    RemediationProposalValidator proposals;

    @MockitoBean
    WorkDispatcher dispatcher;

    @Test
    void aRealModelObservesARealTargetAndDecidesAgain() throws IOException {
        InvestigationFixture fixture = InvestigationFixture.reset(jdbc);
        long incident = fixture.incidentId();
        seedIncident(incident, fixture.streamId());

        orchestrator.runInvestigation(incident, 1);

        String transcript = transcript(incident);
        Path out = Path.of("target", "real-llm-closure.txt");
        Files.writeString(out, transcript, StandardCharsets.UTF_8);
        System.out.println(transcript);

        // 真实模型的 REQUEST_CAPABILITY → 真实 Provider 成功并产生 Observation → 之后某一步的请求上下文含该 Observation 且模型再次作答
        List<Map<String, Object>> observed = jdbc.queryForList(
                "SELECT o.id AS observation, ci.id AS invocation FROM observation o"
                        + " JOIN capability_invocation ci ON ci.id = o.capability_invocation_id"
                        + " WHERE ci.incident_id = ? AND ci.status = 'SUCCEEDED' ORDER BY o.id",
                incident);
        assertThat(observed).as("real observations").isNotEmpty();
        long firstObservation = ((Number) observed.getFirst().get("observation")).longValue();
        List<Long> answeredAfter = REQUESTS.stream()
                .filter(request -> request.observations().stream().anyMatch(o -> o.id() == firstObservation))
                .map(InvestigationStepRequest::stepId)
                .toList();
        assertThat(answeredAfter).as("steps that saw the observation").isNotEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_step_record WHERE id IN (" + join(answeredAfter) + ")"
                                + " AND status = 'SUCCEEDED' AND model_provider = 'openai-compatible' AND model_name = ?",
                        Integer.class,
                        MODEL))
                .as("real model answered after the observation")
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_step_record WHERE incident_id = ? AND status = 'SUCCEEDED'"
                                + " AND intent_type = 'REQUEST_CAPABILITY' AND model_name = ?",
                        Integer.class,
                        incident,
                        MODEL))
                .as("real model requested a capability")
                .isPositive();
    }

    /**
     * 08 TASK-063～064：真实模型用 remediation-v1 为已诊断的 Incident 提出处理建议。上下文由生产 RemediationDraftContextBuilder 构造
     * （系统中另有一个可重启但与诊断无关的服务，不会进入 allowedActions），经生产 HttpAiRuntimeClient 调用，再由 Java 校验并给出
     * riskLevel / requiresApproval。运行记录写入 target/real-llm-remediation.txt。
     */
    @Test
    void aRealModelProposesARemediationFromTheAllowedActions() throws IOException {
        RemediationFixture seeded = RemediationFixture.seed(jdbc);
        RemediationDraftContext context = remediationContexts.build(seeded.incidentKey(), 7);

        var response = ai.draftRemediation(context.request());
        ValidatedRemediationProposal proposal =
                proposals.validate(context.allowedActions(), context.diagnosisId(), response);

        String transcript = "# TASK-063/064 real LLM remediation run\nmodel=" + MODEL + "\nrequest=" + context.request()
                + "\nresponse=" + response + "\nvalidated=" + proposal + "\n";
        Files.writeString(Path.of("target", "real-llm-remediation.txt"), transcript, StandardCharsets.UTF_8);
        System.out.println(transcript);
        assertThat(context.request().allowedActions())
                .singleElement()
                .satisfies(action -> assertThat(action.resourceKey()).isEqualTo("statistics-consumer"));
        assertThat(proposal.capabilityKey()).isEqualTo("service.restart");
        assertThat(proposal.target().id()).isEqualTo(seeded.consumer());
        assertThat(proposal.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(proposal.requiresApproval()).isTrue();
        assertThat(proposal.parameterPayload()).isEqualTo("{}");
    }

    // ---------------------------------------------------------------- data

    private void seedIncident(long incident, long stream) {
        for (String table : List.of("capability_binding", "resource_binding", "data_source_connection")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update(
                "UPDATE incident SET title = '短链访问统计停止更新', impact_summary = '访问统计数据约 10 分钟未更新；"
                        + "短链跳转请求仍正常返回。' WHERE id = ?",
                incident);
        long system = jdbc.queryForObject("SELECT id FROM managed_system", Long.class);
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (" + system + ", 'statistics-consumer', '统计消费者', 'SERVICE',"
                + " 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        long consumer = jdbc.queryForObject(
                "SELECT id FROM managed_resource WHERE resource_key = 'statistics-consumer'", Long.class);
        for (long resource : List.of(stream, consumer)) {
            jdbc.update(
                    "INSERT INTO incident_affected_resource (incident_id, managed_resource_id, created_at)"
                            + " VALUES (?, ?, UTC_TIMESTAMP(3))",
                    incident,
                    resource);
        }
        long redis = connection("redis-local", "REDIS", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        long docker = connection("docker-local", "DOCKER", "unix:///var/run/docker.sock");
        bind(
                stream,
                redis,
                "redis.resource.binding",
                "{\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"stats-consumer-group\"}");
        bind(consumer, docker, "docker.resource.binding", "{\"containerName\": \"" + CONSUMER + "\"}");
        capability(stream, "queue.inspect");
        capability(consumer, "service.inspect");
    }

    private long connection(String key, String providerType, String endpoint) {
        jdbc.update(
                "INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, config_schema_name,"
                        + " config_schema_version, config_payload, status, created_at, updated_at) VALUES (?, ?, ?, ?,"
                        + " ?, 1, '{}', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                key,
                key,
                providerType,
                endpoint,
                providerType.toLowerCase() + ".connection.config");
        return jdbc.queryForObject("SELECT id FROM data_source_connection WHERE connection_key = ?", Long.class, key);
    }

    private void bind(long resource, long connection, String schemaName, String payload) {
        jdbc.update(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                        + " selector_schema_version, selector_payload, created_at, updated_at) VALUES (?, ?, ?, 1, ?,"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resource,
                connection,
                schemaName,
                payload);
    }

    private void capability(long resource, String key) {
        jdbc.update(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resource,
                key);
    }

    // ---------------------------------------------------------------- transcript

    private String transcript(long incident) {
        StringBuilder out = new StringBuilder("# TASK-058 real LLM closure run\n");
        out.append("model=").append(MODEL).append('\n');
        for (InvestigationStepRequest request : REQUESTS) {
            out.append("request stepId=")
                    .append(request.stepId())
                    .append(" observations=")
                    .append(request.observations().stream().map(o -> o.id()).toList())
                    .append(" recentTimeline=")
                    .append(request.recentTimeline().stream()
                            .map(InvestigationStepRequest.TimelineEntry::eventType)
                            .toList())
                    .append('\n');
        }
        section(
                out,
                "agent steps",
                "SELECT CONCAT_WS(' | ', id, step_no, status, intent_type, model_provider, model_name,"
                        + " prompt_template_version, CONCAT('tokens=', prompt_tokens, '/', completion_tokens),"
                        + " CONCAT('latency=', latency_ms, 'ms'), error_code, CAST(output_payload AS CHAR))"
                        + " FROM agent_step_record WHERE incident_id = ? ORDER BY id",
                incident);
        section(
                out,
                "capability invocations",
                "SELECT CONCAT_WS(' | ', id, capability_key, status, error_code, CAST(response_payload AS CHAR))"
                        + " FROM capability_invocation WHERE incident_id = ? ORDER BY id",
                incident);
        section(
                out,
                "observations",
                "SELECT CONCAT_WS(' | ', id, capability_invocation_id, observation_kind, summary) FROM observation"
                        + " WHERE incident_id = ? ORDER BY id",
                incident);
        section(
                out,
                "hypotheses",
                "SELECT CONCAT_WS(' | ', h.id, h.status, h.title) FROM hypothesis h"
                        + " JOIN investigation i ON i.id = h.investigation_id WHERE i.incident_id = ? ORDER BY h.id",
                incident);
        section(
                out,
                "evidence",
                "SELECT CONCAT_WS(' | ', e.id, e.observation_id, e.hypothesis_id, e.relation, e.reason) FROM evidence e"
                        + " JOIN investigation i ON i.id = e.investigation_id WHERE i.incident_id = ? ORDER BY e.id",
                incident);
        section(
                out,
                "diagnosis",
                "SELECT CONCAT_WS(' | ', d.version_no, d.conclusion_type, d.primary_hypothesis_id, d.summary)"
                        + " FROM diagnosis d JOIN investigation i ON i.id = d.investigation_id WHERE i.incident_id = ?",
                incident);
        section(
                out,
                "timeline",
                "SELECT CONCAT_WS(' | ', id, event_type, summary) FROM incident_timeline_event WHERE incident_id = ?"
                        + " ORDER BY id",
                incident);
        out.append("incident status=")
                .append(jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incident))
                .append('\n');
        return out.toString();
    }

    private void section(StringBuilder out, String title, String sql, long incident) {
        out.append("## ").append(title).append('\n');
        jdbc.queryForList(sql, String.class, incident)
                .forEach(line -> out.append(line).append('\n'));
    }

    // ---------------------------------------------------------------- helpers

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RealLlmClosureRun needs -D" + name);
        }
        return value;
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static String redis(String... command) throws Exception {
        String[] full = new String[command.length + 1];
        full[0] = "redis-cli";
        System.arraycopy(command, 0, full, 1, command.length);
        var result = REDIS.execInContainer(full);
        assertThat(result.getExitCode()).as(String.join(" ", command)).isZero();
        return result.getStdout().strip();
    }

    private static void docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("opspilot-closure-raw");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
