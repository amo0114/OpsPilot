package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.logs.LogPatternAggregator;
import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * 08 TASK-053：真实 Loki（grafana/loki:3.5.3）。先经 push API 写入已知日志，再经 Provider 查询：级别筛选（含 WARNING）、不区分大小写且
 * 须同时出现的普通文本关键字、含正则元字符与引号/反斜杠的关键字只按字面匹配、其他应用的日志不进入、原始匹配上限与截断、窗口边界，
 * 以及样例与原始结果的脱敏。
 */
class LokiLogsSearchProviderIntegrationTest {

    static GenericContainer<?> loki;
    static final Instant NOW = Instant.now();
    static final Instant BASE = NOW.minusSeconds(300);

    @BeforeAll
    static void pushKnownLogs() throws Exception {
        loki = new GenericContainer<>("grafana/loki:3.5.3")
                .withExposedPorts(3100)
                .waitingFor(Wait.forHttp("/ready").forPort(3100).withStartupTimeout(Duration.ofMinutes(2)));
        loki.start();
        List<List<String>> shortlink = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            shortlink.add(entry(
                    i,
                    "2026-09-29T08:00:00.000Z ERROR 1 --- [shortlink] c.n.s.RedirectService :"
                            + " Redis command timed out after " + (2000 + i) + " ms password=hunter2"));
        }
        for (int i = 0; i < 30; i++) {
            shortlink.add(entry(150 + i, "WARN Connection refused to 10.0.0." + i + ":6379"));
        }
        for (int i = 0; i < 20; i++) {
            shortlink.add(entry(180 + i, "INFO request ok in " + i + " ms"));
        }
        for (int i = 0; i < 3; i++) {
            shortlink.add(entry(200 + i, "WARNING slow query " + i));
        }
        shortlink.add(entry(203, "ERROR value a.b(c) failed"));
        shortlink.add(entry(204, "ERROR value a.b(c) failed again"));
        shortlink.add(entry(205, "ERROR value aXb(c) failed"));
        shortlink.add(entry(206, "ERROR value aXbXc failed"));
        shortlink.add(entry(207, "ERROR quote \"x\" and \\path Authorization: Bearer abc.def"));
        // 正文中出现 ERROR 的 INFO 行，且比所有 ERROR 行更新（B16-R1）
        for (int i = 0; i < 40; i++) {
            shortlink.add(entry(210 + i, "2026-09-29 10:00:00 INFO Retrying request after ERROR from upstream " + i));
        }
        push("shortlink-project", shortlink);
        push("another-app", List.of(entry(10, "ERROR Redis command timed out after 1 ms")));
    }

    @AfterAll
    static void stop() {
        if (loki != null) {
            loki.stop();
        }
    }

    @Test
    void levelsAreFilteredAndLinesAggregatedIntoSanitizedPatterns() {
        ProviderOutcome.Fetched fetched = fetch(List.of(LogSeverity.ERROR, LogSeverity.WARN), List.of(), 500);
        LogsSearchResultV1 result = (LogsSearchResultV1) fetched.result();

        // INFO 被排除、WARNING 计为 WARN、另一个应用不进入：150 + 30 + 3 + 5
        assertThat(result.totalMatches()).isEqualTo(188);
        assertThat(result.truncated()).isFalse();
        assertThat(result.patterns()).extracting(LogPattern::count).startsWith(150L, 30L, 3L);
        LogPattern redis = result.patterns().getFirst();
        assertThat(redis.pattern())
                .isEqualTo("<TIME> ERROR <NUM> --- [shortlink] c.n.s.RedirectService : Redis command timed out after"
                        + " <NUM> ms password=[REDACTED]");
        assertThat(redis.severity()).isEqualTo(LogSeverity.ERROR);
        assertThat(redis.firstSeen()).isEqualTo(BASE);
        assertThat(redis.lastSeen()).isEqualTo(BASE.plusSeconds(149));
        assertThat(result.patterns().get(1).pattern()).isEqualTo("WARN Connection refused to <IP>");
        assertThat(result.patterns().get(2).severity()).isEqualTo(LogSeverity.WARN);
        assertThat(result.toString()).doesNotContain("hunter2").doesNotContain("abc.def");
        // 原始结果：一条日志一行，已按时间排序（脱敏由结果管线完成）
        assertThat(fetched.rawResult().lines()).hasSize(188);
        assertThat(fetched.rawResult().lines().findFirst().orElseThrow()).startsWith(BASE + "\t");
    }

    @Test
    void keywordsArePlainCaseInsensitiveTextThatMustAllAppear() {
        LogsSearchResultV1 both = result(List.of(LogSeverity.ERROR), List.of("REDIS", "timed OUT"), 500);
        assertThat(both.totalMatches()).isEqualTo(150);

        LogsSearchResultV1 literal = result(List.of(LogSeverity.ERROR), List.of("a.b(c)"), 500);
        assertThat(literal.totalMatches()).isEqualTo(2); // aXb(c) 与 aXbXc 不按正则匹配

        LogsSearchResultV1 quoted = result(List.of(LogSeverity.ERROR), List.of("\"x\" and \\path"), 500);
        assertThat(quoted.totalMatches()).isEqualTo(1);
        assertThat(quoted.patterns().getFirst().samples().getFirst())
                .isEqualTo("ERROR quote \"x\" and \\path Authorization: [REDACTED]");

        assertThat(result(List.of(LogSeverity.DEBUG), List.of(), 500).totalMatches())
                .isZero();
    }

    /**
     * B16-R1：级别取行内第一个级别词并在 Loki 内先筛选：正文含 ERROR 的 INFO 行不会被 ERROR 查询取回，也不会在原始上限内挤掉真正的
     * ERROR 行；按 INFO 查询时它们以 INFO 返回。
     */
    @Test
    void levelsFollowTheFirstLevelWordEvenWhenTheMessageMentionsAnother() {
        LogsSearchResultV1 errors = result(List.of(LogSeverity.ERROR), List.of(), 5);
        assertThat(errors.truncated()).isTrue();
        assertThat(errors.totalMatches()).isEqualTo(5);
        assertThat(errors.patterns()).allSatisfy(pattern -> {
            assertThat(pattern.severity()).isEqualTo(LogSeverity.ERROR);
            assertThat(pattern.pattern()).doesNotContain("Retrying");
        });

        LogsSearchResultV1 infos = result(List.of(LogSeverity.INFO), List.of(), 500);
        assertThat(infos.totalMatches()).isEqualTo(60); // 20 条普通 INFO + 40 条正文含 ERROR 的 INFO
        assertThat(infos.patterns()).extracting(LogPattern::severity).containsOnly(LogSeverity.INFO);
    }

    /** 原始匹配达到上限即截断：只取最新的 N 行，计数标为下限。 */
    @Test
    void theRawMatchLimitTruncatesToTheNewestLines() {
        LogsSearchResultV1 result = result(List.of(LogSeverity.ERROR), List.of("redis"), 5);

        assertThat(result.truncated()).isTrue();
        assertThat(result.totalMatches()).isEqualTo(5);
        assertThat(result.patterns().getFirst().firstSeen()).isEqualTo(BASE.plusSeconds(145));
    }

    @Test
    void onlyLinesInsideTheWindowAreRead() {
        ResolvedWindow early = new ResolvedWindow(new QueryWindow(BASE.minusSeconds(600), BASE), null);
        LogsSearchResultV1 none = (LogsSearchResultV1) ((ProviderOutcome.Fetched) ProviderInvocations.fetch(
                        provider(500), invocation(List.of(LogSeverity.ERROR), List.of(), early)))
                .result();
        assertThat(none.totalMatches()).isZero();
        assertThat(none.patterns()).isEmpty();

        // [BASE, BASE + 10s)：恰在 end 的第 10 行不在内
        ResolvedWindow tenSeconds = new ResolvedWindow(new QueryWindow(BASE, BASE.plusSeconds(10)), null);
        LogsSearchResultV1 ten = (LogsSearchResultV1) ((ProviderOutcome.Fetched) ProviderInvocations.fetch(
                        provider(500), invocation(List.of(LogSeverity.ERROR), List.of(), tenSeconds)))
                .result();
        assertThat(ten.totalMatches()).isEqualTo(10);
    }

    // ---------------------------------------------------------------- helpers

    private static LogsSearchResultV1 result(List<LogSeverity> severity, List<String> keywords, int limit) {
        return (LogsSearchResultV1) fetch(severity, keywords, limit).result();
    }

    private static ProviderOutcome.Fetched fetch(List<LogSeverity> severity, List<String> keywords, int limit) {
        ResolvedWindow window = new ResolvedWindow(new QueryWindow(BASE.minusSeconds(60), NOW), null);
        ProviderOutcome outcome = ProviderInvocations.fetch(provider(limit), invocation(severity, keywords, window));
        assertThat(outcome).isInstanceOf(ProviderOutcome.Fetched.class);
        return (ProviderOutcome.Fetched) outcome;
    }

    private static LokiLogsSearchProvider provider(int limit) {
        LogsSettings settings = new LogsSettings(limit, 10, 2, 5, 2000);
        return new LokiLogsSearchProvider(
                new ProviderHttpClient(Clock.systemUTC(), 8L * 1024 * 1024),
                ProviderInvocations.noCredentials(),
                new LogPatternAggregator(new Sanitizer(new SanitizerSettings(true)), settings),
                Clock.systemUTC(),
                limit);
    }

    private static AdmittedInvocation invocation(
            List<LogSeverity> severity, List<String> keywords, ResolvedWindow window) {
        return ProviderInvocations.admitted(
                CapabilityKey.LOGS_SEARCH,
                ProviderType.LOKI,
                "http://" + loki.getHost() + ":" + loki.getMappedPort(3100),
                null,
                new LokiResourceBindingV1(Map.of("app", "shortlink-project")),
                new LogsSearchArgumentsV1(WindowKey.LAST_15_MIN, severity, keywords),
                window,
                Duration.ofSeconds(15));
    }

    private static List<String> entry(int secondsAfterBase, String line) {
        Instant at = BASE.plusSeconds(secondsAfterBase);
        return List.of(Long.toString(at.getEpochSecond() * 1_000_000_000L + at.getNano()), line);
    }

    private static void push(String app, List<List<String>> values) throws Exception {
        String body = JsonMapper.builder()
                .build()
                .writeValueAsString(Map.of("streams", List.of(Map.of("stream", Map.of("app", app), "values", values))));
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://" + loki.getHost() + ":" + loki.getMappedPort(3100)
                                        + "/loki/api/v1/push"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
    }
}
