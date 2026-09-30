package io.github.ismoyuan.opspilot.web.recovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryCommand;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryResult;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 05 §34、§91、§93：verify-recovery 的 202、响应包络、请求校验与错误码映射；用例以桩替代。 */
@WebMvcTest(RecoveryController.class)
class RecoveryControllerTest {

    private static final String KEY = "INC-20260930-0001";
    private static final String BODY = "{\"expectedVersion\": 11, \"resourceKey\": \"statistics-consumer\","
            + " \"note\": \"已在服务器手工恢复 Statistics Consumer。\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RecoveryVerificationApplicationService verifications;

    /** 05 §34：202，Incident → VERIFYING，返回新 Verification 的编号与 PENDING 以及 Incident 版本。 */
    @Test
    void verifyRecoveryIsAcceptedWithThePendingVerification() throws Exception {
        given(verifications.verifyRecovery(new VerifyRecoveryCommand(
                        KEY, 11, "statistics-consumer", "已在服务器手工恢复 Statistics Consumer。", "demo-user")))
                .willReturn(new VerifyRecoveryResult(
                        new IncidentKey(KEY), IncidentStatus.VERIFYING, 12, 2, RecoveryVerificationStatus.PENDING));

        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/verify-recovery")
                        .header(RequestIdFilter.HEADER, "req_verify-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.incidentKey").value(KEY))
                .andExpect(jsonPath("$.data.incidentStatus").value("VERIFYING"))
                .andExpect(jsonPath("$.data.incidentVersion").value(12))
                .andExpect(jsonPath("$.data.verificationNo").value(2))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value("req_verify-1"));
    }

    /** 05 §93～§94：拒绝分支映射为规定的 HTTP 状态与错误码。 */
    @ParameterizedTest
    @CsvSource({
        "INCIDENT_NOT_FOUND, 404",
        "INCIDENT_STATE_CONFLICT, 409",
        "INCIDENT_VERSION_CONFLICT, 409",
        "RESOURCE_NOT_IN_SYSTEM, 422",
        "RECOVERY_VERIFICATION_ALREADY_RUNNING, 409",
        "RECOVERY_POLICY_NOT_FOUND, 422",
        "RECOVERY_POLICY_AMBIGUOUS, 422"
    })
    void rejectionsUseTheirCodes(ErrorCode code, int httpStatus) throws Exception {
        given(verifications.verifyRecovery(any())).willThrow(new ApplicationException(code, "x", Map.of()));

        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/verify-recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"expectedVersion\": 11}",
                "{\"expectedVersion\": 11, \"resourceKey\": \" \"}",
                "{\"expectedVersion\": -1, \"resourceKey\": \"statistics-consumer\"}"
            })
    void requestBodiesAreValidatedBeforeTheUseCase(String body) throws Exception {
        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/verify-recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        verifyNoInteractions(verifications);
    }
}
