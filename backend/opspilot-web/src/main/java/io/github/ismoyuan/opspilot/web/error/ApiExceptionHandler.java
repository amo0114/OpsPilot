package io.github.ismoyuan.opspilot.web.error;

import io.github.ismoyuan.opspilot.domain.error.ErrorCategory;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 把业务异常与 Spring MVC 异常统一映射为 05 §9 错误响应；message 取 ErrorCode 固定文案。
 *
 * <p>异常 message 与 cause 可能携带 Secret、连接串或原始载荷（07 §99、05 §96），响应和日志都不输出；
 * 日志只记录错误码、requestId 与异常链的类型和抛出位置。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OpsPilotException.class)
    ResponseEntity<Object> handleOpsPilot(OpsPilotException ex, HttpServletRequest request) {
        HttpStatus status = statusOf(ex.errorCode().category());
        if (status.is5xxServerError()) {
            log.warn(
                    "Request failed: code={} requestId={} exception={}",
                    ex.errorCode(),
                    RequestIdFilter.requestId(request),
                    describe(ex));
        }
        return respond(status, HttpHeaders.EMPTY, ex.errorCode(), ex.details(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error(
                "Unhandled exception: code={} requestId={} exception={}",
                ErrorCode.INTERNAL_ERROR,
                RequestIdFilter.requestId(request),
                describe(ex));
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, ErrorCode.INTERNAL_ERROR, Map.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", Objects.requireNonNullElse(error.getDefaultMessage(), "")))
                .toList();
        return respond(
                status,
                headers,
                ErrorCode.REQUEST_VALIDATION_FAILED,
                Map.of("fieldErrors", fieldErrors),
                servletRequest(request));
    }

    /** 其余 Spring MVC 标准异常（405、415、请求体无法解析、找不到路由等）保留其 HTTP 状态，只替换响应体。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest
                && servletWebRequest.getResponse() != null
                && servletWebRequest.getResponse().isCommitted()) {
            return null;
        }
        ErrorCode code;
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            code = ErrorCode.RESOURCE_NOT_FOUND;
        } else if (status.is4xxClientError()) {
            code = ErrorCode.REQUEST_VALIDATION_FAILED;
        } else {
            log.error(
                    "Spring MVC request processing failed: status={} requestId={} exception={}",
                    status.value(),
                    RequestIdFilter.requestId(servletRequest(request)),
                    describe(ex));
            code = ErrorCode.INTERNAL_ERROR;
        }
        return respond(status, headers, code, Map.of(), servletRequest(request));
    }

    /** 05 §94。 */
    static HttpStatus statusOf(ErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEPENDENCY_INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case DEPENDENCY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 异常链摘要：每层只有类型与首个栈帧，不含 message。 */
    static String describe(Throwable ex) {
        StringJoiner chain = new StringJoiner(" <- caused by ");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = ex; current != null && seen.add(current); current = current.getCause()) {
            StackTraceElement[] frames = current.getStackTrace();
            String type = current.getClass().getName();
            chain.add(frames.length == 0 ? type : type + " at " + frames[0]);
        }
        return chain.toString();
    }

    private static ResponseEntity<Object> respond(
            HttpStatusCode status,
            HttpHeaders headers,
            ErrorCode code,
            Map<String, Object> details,
            HttpServletRequest request) {
        ErrorResponse body =
                new ErrorResponse(code.name(), code.defaultMessage(), RequestIdFilter.requestId(request), details);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }
}
