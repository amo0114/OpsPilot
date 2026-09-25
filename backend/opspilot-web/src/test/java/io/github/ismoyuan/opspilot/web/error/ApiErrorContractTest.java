package io.github.ismoyuan.opspilot.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 05 §9～§10、§94、§96：错误响应包络、HTTP 状态、X-Request-Id 与错误日志不泄露敏感内容。 */
@WebMvcTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ApiErrorContractTest.ProbeController.class)
class ApiErrorContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void businessExceptionUsesFixedMessageAndStructuredDetails() throws Exception {
        mvc.perform(get("/probe/missing").header(RequestIdFilter.HEADER, "req_probe-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(RequestIdFilter.HEADER, "req_probe-1"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.defaultMessage()))
                .andExpect(jsonPath("$.requestId").value("req_probe-1"))
                .andExpect(jsonPath("$.details.resourceKey").value("probe-1"))
                .andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingMessageOrCauseToResponseOrLog(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/probe/boom").header(RequestIdFilter.HEADER, "req_boom-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(content().string(not(containsString("hunter2"))));

        assertThat(output)
                .contains("code=INTERNAL_ERROR", "requestId=req_boom-1", "java.lang.IllegalStateException at ")
                .contains("caused by java.io.UncheckedIOException at ")
                .doesNotContain("hunter2", "sk-cause-secret");
    }

    @Test
    void serverSideBusinessExceptionLogsCodeAndTypesOnly(CapturedOutput output) throws Exception {
        mvc.perform(get("/probe/translated").header(RequestIdFilter.HEADER, "req_translated-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("sk-"))));

        assertThat(output)
                .contains("code=INTERNAL_ERROR", "requestId=req_translated-1")
                .contains(ApplicationException.class.getName(), "caused by java.net.SocketTimeoutException at ")
                .doesNotContain("sk-message-secret", "sk-cause-secret");
    }

    @Test
    void springMvcServerErrorLogsTypeWithoutMessage(CapturedOutput output) throws Exception {
        mvc.perform(get("/probe/pathvar").header(RequestIdFilter.HEADER, "req_pathvar-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output)
                .contains("status=500", "requestId=req_pathvar-1", "MissingPathVariableException")
                .doesNotContain("URI template variable");
    }

    @Test
    void beanValidationFailureListsFieldsWithoutEchoingInput() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("name"));
    }

    @Test
    void unreadableBodyIsValidationFailure() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void unknownRouteIsResourceNotFound() throws Exception {
        mvc.perform(get("/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unsupportedMethodKeepsHttpStatusAndAllowHeader() throws Exception {
        mvc.perform(post("/probe/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void missingRequestIdIsGeneratedAndSharedByHeaderBodyAndCorrelation() throws Exception {
        var result = mvc.perform(get("/probe/context"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("req_[0-9a-f]{32}")))
                .andReturn();
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        mvc.perform(get("/probe/context").header(RequestIdFilter.HEADER, requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.correlationId").value(requestId));
    }

    @Test
    void malformedRequestIdIsReplacedNotEchoed() throws Exception {
        mvc.perform(get("/probe/missing").header(RequestIdFilter.HEADER, "bad id\" injected"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("req_[0-9a-f]{32}")))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("req_[0-9a-f]{32}")));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/missing")
        String missing() {
            throw new DomainException(
                    ErrorCode.RESOURCE_NOT_FOUND, "internal detail", Map.of("resourceKey", "probe-1"));
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException(
                    "database password=hunter2",
                    new java.io.UncheckedIOException(new java.io.IOException("token sk-cause-secret")));
        }

        @GetMapping("/probe/translated")
        String translated() {
            throw new ApplicationException(
                    ErrorCode.INTERNAL_ERROR,
                    "provider call with key sk-message-secret",
                    Map.of(),
                    new java.net.SocketTimeoutException("sk-cause-secret"));
        }

        @GetMapping("/probe/pathvar")
        String pathVariableNotInTemplate(@PathVariable("id") String id) {
            return id;
        }

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody ProbeBody body) {
            return body.name();
        }

        @GetMapping("/probe/context")
        Map<String, String> context() {
            return Map.of("requestId", MDC.get(RequestIdFilter.MDC_KEY), "correlationId", MDC.get(Correlation.MDC_KEY));
        }
    }

    record ProbeBody(@NotBlank String name) {}
}
