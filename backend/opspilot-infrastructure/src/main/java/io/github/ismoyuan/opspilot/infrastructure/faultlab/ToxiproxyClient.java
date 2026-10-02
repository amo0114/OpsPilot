package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment.LatencyToxic;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment.ProxyState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Toxiproxy HTTP 控制 API 的最小客户端（08 TASK-094、09 §9）：只读取一个代理、在它上面增加 downstream latency toxic、删除指定 toxic；不创建或
 * 删除代理，不改其他配置。每次请求整体（含响应体）受期限约束，到期取消。失败为固定、脱敏的说明（不含端点）。
 */
final class ToxiproxyClient {

    /** 代理与 toxic 名称：只能是简单标识，不会形成其他路径。 */
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}");

    private final URI endpoint;
    private final String proxyName;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    ToxiproxyClient(URI endpoint, String proxyName, Clock clock) {
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Toxiproxy endpoint must be http://host:port");
        }
        this.endpoint = endpoint;
        this.proxyName = requireName(proxyName);
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    ProxyState proxy(Instant deadline) {
        HttpResponse<byte[]> response =
                send(HttpRequest.newBuilder(uri("/proxies/" + proxyName)).GET(), deadline);
        if (response.statusCode() == 404) {
            throw new FaultInjectionException("Demo environment: the Redis proxy does not exist");
        }
        if (response.statusCode() != 200) {
            throw new FaultInjectionException("Demo environment: Toxiproxy could not read the Redis proxy");
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode toxics = root.path("toxics");
            if (!root.path("enabled").isBoolean() || !toxics.isArray()) {
                throw invalid();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode toxic : toxics) {
                if (!toxic.path("name").isString()) {
                    throw invalid();
                }
                names.add(toxic.path("name").asString());
            }
            return new ProxyState(root.path("enabled").asBoolean(), names);
        } catch (JacksonException ex) {
            throw invalid();
        }
    }

    void addLatency(LatencyToxic toxic, Instant deadline) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("latency", toxic.latencyMs());
        attributes.put("jitter", toxic.jitterMs());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", requireName(toxic.name()));
        body.put("type", "latency");
        // 明确方向：Redis → 客户端（09 §30）
        body.put("stream", "downstream");
        body.put("toxicity", toxic.toxicity());
        body.put("attributes", attributes);
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/proxies/" + proxyName + "/toxics"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body), StandardCharsets.UTF_8)),
                deadline);
        if (response.statusCode() != 200) {
            throw new FaultInjectionException(
                    response.statusCode() == 409
                            ? "Demo environment: the latency toxic already exists"
                            : "Demo environment: Toxiproxy rejected the latency toxic");
        }
    }

    /** @return 删除前是否存在 */
    boolean removeToxic(String toxicName, Instant deadline) {
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/proxies/" + proxyName + "/toxics/" + requireName(toxicName)))
                        .DELETE(),
                deadline);
        return switch (response.statusCode()) {
            case 204, 200 -> true;
            case 404 -> false;
            default -> throw new FaultInjectionException("Demo environment: Toxiproxy rejected the toxic removal");
        };
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request, Instant deadline) {
        return BoundedHttp.send(http, request, deadline, clock, "Toxiproxy");
    }

    private URI uri(String path) {
        return endpoint.resolve(path);
    }

    private static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Toxiproxy names must be simple identifiers");
        }
        return name;
    }

    private static FaultInjectionException invalid() {
        return new FaultInjectionException("Demo environment: Toxiproxy returned an invalid proxy description");
    }
}
