package io.github.ismoyuan.opspilot.infrastructure.ai;

import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AiDecisionPort 的 HTTP/JSON 实现（08 TASK-034、05 §74～§90）。每次调用恰好一次 POST：JDK HttpClient 不重放 POST、
 * 不跟随重定向，本类也不重试（07 §86），失败后是否再问由编排显式开启新 Step。请求带内部 Token 与 correlationId，
 * 从发出到读完整个响应体都不超过调用方给定上限，到期取消；响应经 {@link AiProtocolCodec} 严格解码，并核对回显与所发请求一致（BND-015）。
 *
 * <p>失败映射：超时 → AI_RUNTIME_TIMEOUT；连接失败、401/403、5xx → AI_RUNTIME_UNAVAILABLE；502 与非法/不符回显 →
 * AI_OUTPUT_INVALID；其他 4xx 表示本端请求或配置不符合协议 → INTERNAL_ERROR。错误与日志不含 Token 或响应原文。
 */
public class HttpAiRuntimeClient implements AiDecisionPort {

    static final String INVESTIGATION_STEP_PATH = "/internal/v1/investigation/step";
    static final String REMEDIATION_DRAFT_PATH = "/internal/v1/remediation/draft";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String MODEL_PROVIDER_HEADER = "X-OpsPilot-Model-Provider";
    static final String MODEL_NAME_HEADER = "X-OpsPilot-Model-Name";
    static final String PROMPT_TEMPLATE_HEADER = "X-OpsPilot-Prompt-Template-Version";
    static final String PROMPT_TOKENS_HEADER = "X-OpsPilot-Prompt-Tokens";
    static final String COMPLETION_TOKENS_HEADER = "X-OpsPilot-Completion-Tokens";

    private static final Logger log = LoggerFactory.getLogger(HttpAiRuntimeClient.class);

    private final AiRuntimeProperties properties;
    private final AiProtocolCodec codec;
    private final HttpClient http;

    public HttpAiRuntimeClient(AiRuntimeProperties properties, AiProtocolCodec codec) {
        this.properties = properties;
        this.codec = codec;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (!properties.tokenConfigured()) {
            log.warn("AI runtime token is not configured; AI calls will fail as AI_RUNTIME_UNAVAILABLE");
        }
    }

    @Override
    public InvestigationStepDecision decideInvestigationStep(InvestigationStepRequest request, Duration maxWait) {
        HttpResponse<String> http = post(INVESTIGATION_STEP_PATH, request, request.correlationId(), maxWait);
        InvestigationStepResponse response = decode(http, InvestigationStepResponse.class, request.correlationId());
        if (response.runNo() != request.runNo() || response.stepId() != request.stepId()) {
            throw invalidOutput("investigation step echo does not match the request", request.correlationId());
        }
        return new InvestigationStepDecision(response, metadata(http));
    }

    @Override
    public RemediationDraftResponse draftRemediation(RemediationDraftRequest request) {
        HttpResponse<String> http =
                post(REMEDIATION_DRAFT_PATH, request, request.correlationId(), properties.remediationTimeout());
        RemediationDraftResponse response = decode(http, RemediationDraftResponse.class, request.correlationId());
        if (!response.correlationId().equals(request.correlationId())) {
            throw invalidOutput("remediation draft echo does not match the request", request.correlationId());
        }
        return response;
    }

    /** @return 200 响应；其他状态已映射为失败 */
    private HttpResponse<String> post(String path, Object body, String correlationId, Duration maxWait) {
        if (maxWait == null || !maxWait.isPositive()) {
            throw new IllegalArgumentException("maxWait must be positive");
        }
        if (!properties.tokenConfigured()) {
            throw failure(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime token not configured", correlationId, null);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(maxWait)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.token())
                .header(CORRELATION_HEADER, correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(codec.encode(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = exchange(request, maxWait, correlationId);
        int status = response.statusCode();
        if (status == 200) {
            return response;
        }
        throw failure(statusCode(status), "AI runtime answered HTTP " + status, correlationId, null, status);
    }

    private <T> T decode(HttpResponse<String> response, Class<T> type, String correlationId) {
        try {
            return codec.decode(response.body(), type);
        } catch (ApplicationException ex) {
            log.warn("AI runtime output rejected: correlationId={} message={}", correlationId, type.getSimpleName());
            throw ex;
        }
    }

    /**
     * 调用元数据只是记录用途（04 §59）：缺失或超出记录列长度/范围的值不记录，不因此否定已通过协议校验的结果。
     */
    static AiCallMetadata metadata(HttpResponse<?> response) {
        return new AiCallMetadata(
                text(response, MODEL_PROVIDER_HEADER, 64),
                text(response, MODEL_NAME_HEADER, 128),
                text(response, PROMPT_TEMPLATE_HEADER, 64),
                count(response, PROMPT_TOKENS_HEADER),
                count(response, COMPLETION_TOKENS_HEADER));
    }

    private static String text(HttpResponse<?> response, String header, int max) {
        return response.headers()
                .firstValue(header)
                .filter(value -> !value.isBlank() && value.length() <= max)
                .orElse(null);
    }

    private static Integer count(HttpResponse<?> response, String header) {
        return response.headers()
                .firstValue(header)
                .filter(value -> value.matches("[0-9]{1,9}"))
                .map(Integer::valueOf)
                .orElse(null);
    }

    /**
     * 上限覆盖完整交换（连接、响应头与整个响应体）：HttpRequest.timeout 只管到响应头，因此再以 future 等待同一上限，
     * 到期取消请求（B08-R1）。取消后不再发起任何请求。
     */
    private HttpResponse<String> exchange(HttpRequest request, Duration maxWait, String correlationId) {
        CompletableFuture<HttpResponse<String>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return pending.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw failure(ErrorCode.AI_RUNTIME_TIMEOUT, "AI runtime did not answer in time", correlationId, ex);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime call interrupted", correlationId, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof HttpConnectTimeoutException) {
                throw failure(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime connect timeout", correlationId, cause);
            }
            if (cause instanceof HttpTimeoutException) {
                throw failure(ErrorCode.AI_RUNTIME_TIMEOUT, "AI runtime did not answer in time", correlationId, cause);
            }
            throw failure(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime unreachable", correlationId, cause);
        }
    }

    /** 502 是 Runtime 判定模型输出非法；504 是其模型后端超时；401/403 说明 Token 配置不一致。 */
    private static ErrorCode statusCode(int status) {
        if (status == 502) {
            return ErrorCode.AI_OUTPUT_INVALID;
        }
        if (status == 504) {
            return ErrorCode.AI_RUNTIME_TIMEOUT;
        }
        if (status == 401 || status == 403 || status >= 500 || status < 400) {
            return ErrorCode.AI_RUNTIME_UNAVAILABLE;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private URI endpoint(String path) {
        String base = properties.baseUrl().toString();
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    private static ApplicationException invalidOutput(String message, String correlationId) {
        return failure(ErrorCode.AI_OUTPUT_INVALID, message, correlationId, null);
    }

    private static ApplicationException failure(ErrorCode code, String message, String correlationId, Throwable cause) {
        return failure(code, message, correlationId, cause, null);
    }

    private static ApplicationException failure(
            ErrorCode code, String message, String correlationId, Throwable cause, Integer status) {
        log.warn("AI runtime call failed: code={} correlationId={} status={}", code, correlationId, status);
        Map<String, Object> details = status == null
                ? Map.of("correlationId", correlationId)
                : Map.of("correlationId", correlationId, "status", status);
        return new ApplicationException(code, message, details, cause);
    }
}
