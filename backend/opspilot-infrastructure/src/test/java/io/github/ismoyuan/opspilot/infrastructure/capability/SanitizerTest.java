package io.github.ismoyuan.opspilot.infrastructure.capability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 08 TASK-049、06 §54：凭据与 Token 强制清理、邮箱按配置、结果幂等、普通运维文本不被改写。 */
class SanitizerTest {

    private final Sanitizer sanitizer = new Sanitizer(new SanitizerSettings(true));

    @Test
    void headerCredentialsAreRedactedToTheEndOfTheLine() {
        assertThat(sanitizer.sanitize("GET /s/abc Authorization: Bearer abc.def-123\nnext line"))
                .isEqualTo("GET /s/abc Authorization: [REDACTED]\nnext line");
        assertThat(sanitizer.sanitize("Proxy-Authorization: Basic dXNlcjpwYXNz"))
                .isEqualTo("Proxy-Authorization: [REDACTED]");
        assertThat(sanitizer.sanitize("Cookie: SESSION=s3cr3t; theme=dark")).isEqualTo("Cookie: [REDACTED]");
        assertThat(sanitizer.sanitize("Set-Cookie: SESSION=s3cr3t; Path=/; HttpOnly"))
                .isEqualTo("Set-Cookie: [REDACTED]");
    }

    @Test
    void bearerTokensAnywhereAreRedacted() {
        assertThat(sanitizer.sanitize("upstream rejected bearer eyJx.yy.zz for client"))
                .isEqualTo("upstream rejected bearer [REDACTED] for client");
    }

    @Test
    void sensitiveKeyValuesAreRedactedInPlainAndJsonForms() {
        assertThat(sanitizer.sanitize("login failed user=alice password=hunter2 retry=3"))
                .isEqualTo("login failed user=alice password=[REDACTED] retry=3");
        assertThat(sanitizer.sanitize("MYSQL_PASSWORD=pw1 REDIS_PASSWORD: 'pw 2' JWT_SECRET=\"x y\""))
                .isEqualTo("MYSQL_PASSWORD=[REDACTED] REDIS_PASSWORD: '[REDACTED]' JWT_SECRET=\"[REDACTED]\"");
        assertThat(sanitizer.sanitize("GET /cb?code=1&access_token=at-1&refresh_token=rt-2&state=ok"))
                .isEqualTo("GET /cb?code=1&access_token=[REDACTED]&refresh_token=[REDACTED]&state=ok");
        assertThat(sanitizer.sanitize("{\"api_key\":\"k-1\",\"apiKey\": \"k\\\"2\",\"x-api-key\":7,\"name\":\"a\"}"))
                .isEqualTo(
                        "{\"api_key\":\"[REDACTED]\",\"apiKey\": \"[REDACTED]\",\"x-api-key\":[REDACTED],\"name\":\"a\"}");
        assertThat(sanitizer.sanitize("{\"Authorization\":\"Bearer abc\",\"Cookie\":\"a=b\"}"))
                .isEqualTo("{\"Authorization\":\"[REDACTED]\",\"Cookie\":\"[REDACTED]\"}");
        assertThat(sanitizer.sanitize("client_secret=cs passwd=p pwd=q credentials=c private_key=pk"))
                .isEqualTo("client_secret=[REDACTED] passwd=[REDACTED] pwd=[REDACTED] credentials=[REDACTED] "
                        + "private_key=[REDACTED]");
    }

    @Test
    void passwordsInsideUrlsAreRedactedButTheRestOfTheUrlIsKept() {
        assertThat(sanitizer.sanitize("jdbc:mysql://opspilot:db-pass@mysql:3306/shortlink?useSSL=false"))
                .isEqualTo("jdbc:mysql://opspilot:[REDACTED]@mysql:3306/shortlink?useSSL=false");
        assertThat(sanitizer.sanitize("connect redis://:r3dis@redis:6379/0 failed"))
                .isEqualTo("connect redis://:[REDACTED]@redis:6379/0 failed");
        assertThat(sanitizer.sanitize("jdbc:mysql://mysql:3306/db?user=root&password=pw"))
                .isEqualTo("jdbc:mysql://mysql:3306/db?user=root&password=[REDACTED]");
    }

    @Test
    void jwtPrivateKeysAndSqlPasswordsAreRedacted() {
        assertThat(sanitizer.sanitize("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig_value- seen"))
                .isEqualTo("token [REDACTED] seen");
        assertThat(sanitizer.sanitize(
                        "key -----BEGIN RSA PRIVATE KEY-----\nMIIE\nabc\n-----END RSA PRIVATE KEY----- end"))
                .isEqualTo("key [REDACTED] end");
        assertThat(sanitizer.sanitize("CREATE USER 'ro'@'%' IDENTIFIED BY 'p@ss'"))
                .isEqualTo("CREATE USER 'ro'@'%' IDENTIFIED BY '[REDACTED]'");
    }

    /** B15-R1 P1：嵌在 JSON 字符串里的 JSON（任意层转义）、SQL 重复引号、URL 编码与未闭合引号都不能漏出凭据，并保留原有引号层级。 */
    @Test
    void credentialsBehindEscapesAndEncodingsAreRedacted() {
        assertThat(sanitizer.sanitize("{\"message\":\"{\\\"password\\\":\\\"secret123\\\"}\"}"))
                .isEqualTo("{\"message\":\"{\\\"password\\\":\\\"[REDACTED]\\\"}\"}");
        assertThat(sanitizer.sanitize("{\"message\":\"password=\\\"secret123\\\"\"}"))
                .isEqualTo("{\"message\":\"password=\\\"[REDACTED]\\\"\"}");
        // 两层嵌套
        assertThat(sanitizer.sanitize("{\"a\":\"{\\\"b\\\":\\\"{\\\\\\\"token\\\\\\\":\\\\\\\"t0k\\\\\\\"}\\\"}\"}"))
                .isEqualTo("{\"a\":\"{\\\"b\\\":\\\"{\\\\\\\"token\\\\\\\":\\\\\\\"[REDACTED]\\\\\\\"}\\\"}\"}");
        // 值内更深一层转义的引号不是闭合
        assertThat(sanitizer.sanitize("{\\\"secret\\\":\\\"p\\\\\\\"q-tail\\\"}"))
                .isEqualTo("{\\\"secret\\\":\\\"[REDACTED]\\\"}");
        assertThat(sanitizer.sanitize("CREATE USER 'ro' IDENTIFIED BY 'abc''def' PASSWORD EXPIRE"))
                .isEqualTo("CREATE USER 'ro' IDENTIFIED BY '[REDACTED]' PASSWORD EXPIRE");
        assertThat(sanitizer.sanitize("password='ab''cd' next")).isEqualTo("password='[REDACTED]' next");
        assertThat(sanitizer.sanitize("GET /cb?q=%7B%22password%22%3A%22s3cr3t%22%7D&x=1"))
                .isEqualTo("GET /cb?q=%7B%22password%22%3A%22[REDACTED]%22%7D&x=1");
        assertThat(sanitizer.sanitize("GET /login?user=a&password%3Ds3cr3t&x=1"))
                .isEqualTo("GET /login?user=a&password%3D[REDACTED]&x=1");
        assertThat(sanitizer.sanitize("upstream said Bearer%20abc.def rejected"))
                .isEqualTo("upstream said Bearer [REDACTED] rejected");
        // 未闭合的引号：遮盖到行尾
        assertThat(sanitizer.sanitize("password=\"abc def\nnext")).isEqualTo("password=\"[REDACTED]\"\nnext");
        for (String input : List.of(
                "{\"message\":\"{\\\"password\\\":\\\"secret123\\\"}\"}",
                "IDENTIFIED BY 'abc''def'",
                "q=%7B%22password%22%3A%22s3cr3t%22%7D",
                "password=\"abc")) {
            String once = sanitizer.sanitize(input);
            assertThat(once).doesNotContain("secret123").doesNotContain("def'").doesNotContain("s3cr3t");
            assertThat(sanitizer.sanitize(once)).as(input).isEqualTo(once);
        }
    }

    @Test
    void emailsFollowTheSetting() {
        String text = "notify ops.team+alerts@example.com about INC-001";
        assertThat(sanitizer.sanitize(text)).isEqualTo("notify [REDACTED] about INC-001");
        assertThat(new Sanitizer(new SanitizerSettings(false)).sanitize(text)).isEqualTo(text);
        // 关闭邮箱规则不影响凭据
        assertThat(new Sanitizer(new SanitizerSettings(false)).sanitize("password=x"))
                .isEqualTo("password=[REDACTED]");
    }

    @Test
    void sanitizingIsIdempotent() {
        List<String> inputs = List.of(
                "Authorization: Bearer abc\nCookie: a=1; b=2",
                "{\"Authorization\":\"Bearer abc\",\"password\":\"p\"}",
                "jdbc:mysql://u:p@h/db?password=x&token=y",
                "a@b.example password: [REDACTED] cookie=[REDACTED]");
        for (String input : inputs) {
            String once = sanitizer.sanitize(input);
            assertThat(sanitizer.sanitize(once)).as(input).isEqualTo(once);
        }
    }

    @Test
    void ordinaryOperationalTextIsUnchanged() {
        List<String> inputs = List.of(
                "Redis command timed out after 2000 ms on shortlink-redis:6379",
                "HikariPool-1 - Connection is not available, request timed out after 30000ms",
                "SELECT `id`, `short_code` FROM `link` WHERE `short_code` = ?",
                "GET https://shortlink.example.com/s/Ab3x 200 12ms",
                "consumer stats-consumer-group lag=2180 pending=4",
                "");
        for (String input : inputs) {
            assertThat(sanitizer.sanitize(input)).isEqualTo(input);
        }
        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.sanitizeRaw(null).content()).isEmpty();
    }

    /** B15-R2 P1：长引号值、大量重复引号或转义、超长邮箱域名都在线性时间内完成，不因正则递归栈溢出，且不漏出内容。 */
    @Test
    void longAdversarialValuesDoNotOverflowTheStack() {
        String longValue = "s".repeat(200_000);
        List<String[]> cases = List.of(
                new String[] {"password=\"" + longValue + "\" next", "password=\"[REDACTED]\" next"},
                new String[] {"password='" + longValue + "' next", "password='[REDACTED]' next"},
                new String[] {"IDENTIFIED BY '" + longValue + "' x", "IDENTIFIED BY '[REDACTED]' x"},
                new String[] {"IDENTIFIED BY '" + "''".repeat(100_000) + "' x", "IDENTIFIED BY '[REDACTED]' x"},
                new String[] {"password='" + "''".repeat(100_000) + "' x", "password='[REDACTED]' x"},
                new String[] {
                    "{\\\"token\\\":\\\"" + "\\\\\\\"".repeat(50_000) + "\\\"}", "{\\\"token\\\":\\\"[REDACTED]\\\"}"
                },
                new String[] {"password=\"" + longValue, "password=\"[REDACTED]\""},
                new String[] {"q=%22password%22%3A%22" + longValue + "%22", "q=%22password%22%3A%22[REDACTED]%22"},
                new String[] {"secret=" + longValue + " tail", "secret=[REDACTED] tail"},
                new String[] {"Authorization: Bearer " + longValue, "Authorization: [REDACTED]"},
                new String[] {"to a@b" + ".c".repeat(100_000) + " ok", "to [REDACTED] ok"},
                new String[] {"-----BEGIN PRIVATE KEY-----" + longValue, "[REDACTED]"});
        long started = System.nanoTime();
        for (String[] item : cases) {
            assertThat(sanitizer.sanitize(item[0])).isEqualTo(item[1]);
        }
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    /** 词首锚定：长串不引起逐位回溯。 */
    @Test
    void longTokenFreeInputIsProcessedQuickly() {
        String blob = "A".repeat(200_000) + " " + "b.".repeat(50_000);
        long started = System.nanoTime();
        assertThat(sanitizer.sanitize(blob)).isEqualTo(blob);
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
    }

    /** 结构化结果中来自外部的自由文本字段均被清理；数值与结构不变。 */
    @Test
    void freeTextFieldsOfResultsAreSanitized() {
        LogsSearchResultV1 logs = (LogsSearchResultV1)
                sanitizer.sanitizeResult(CapabilityResultSamples.logs()).value();
        assertThat(logs.patterns().getFirst().samples())
                .containsExactly(
                        "Redis command timed out after 2000 ms password=[REDACTED]",
                        "GET /s/Ab3x Authorization: [REDACTED]");
        assertThat(logs.totalMatches()).isEqualTo(188);

        DatabaseInspectResultV1 database = (DatabaseInspectResultV1)
                sanitizer.sanitizeResult(CapabilityResultSamples.slowQueries()).value();
        assertThat(database.slowQueries().getFirst().normalizedSql())
                .isEqualTo("SELECT * FROM t_link WHERE short_uri = ? AND token = '[REDACTED]'");

        ServiceInspectResultV1 service = (ServiceInspectResultV1) sanitizer
                .sanitizeResult(new ServiceInspectResultV1(
                        ServiceInspectResultV1.RuntimeState.RUNNING,
                        ServiceInspectResultV1.HealthStatus.HEALTHY,
                        null,
                        0,
                        "https://robot:pat-123@registry.example.com/app:1",
                        null,
                        null))
                .value();
        assertThat(service.image()).isEqualTo("https://robot:[REDACTED]@registry.example.com/app:1");

        for (CapabilityResult unchanged : List.of(
                CapabilityResultSamples.metrics(),
                CapabilityResultSamples.cache(),
                CapabilityResultSamples.connections(),
                CapabilityResultSamples.lockWaits(),
                CapabilityResultSamples.queue(2180L),
                CapabilityResultSamples.service())) {
            assertThat(sanitizer.sanitizeResult(unchanged).value()).isEqualTo(unchanged);
        }
    }
}
