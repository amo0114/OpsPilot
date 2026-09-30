package io.github.ismoyuan.opspilot.web.remediation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ismoyuan.opspilot.application.approval.ApprovalApplicationService;
import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionCommand;
import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionResult;
import io.github.ismoyuan.opspilot.application.approval.ApprovalRepository;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationResult;
import io.github.ismoyuan.opspilot.application.remediation.ValidatedRemediationProposal;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 05 §29～§32、§37～§43：处理建议与审批 API 的状态码、响应包络、请求校验与错误码映射；用例以桩替代。 */
@WebMvcTest(RemediationController.class)
class RemediationControllerTest {

    private static final String KEY = "INC-20260930-0001";
    private static final String DECISION =
            "{\"expectedApprovalVersion\": 0, \"expectedIncidentVersion\": 8, \"comment\": \"当前不希望重启消费者。\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RemediationApplicationService remediations;

    @MockitoBean
    ApprovalApplicationService approvals;

    /** 05 §32：201，Incident 转 AWAITING_APPROVAL，Plan / Action（Java 的 riskLevel）/ PENDING Approval。 */
    @Test
    void requestRemediationReturnsThePlanActionAndPendingApproval() throws Exception {
        ManagedResource consumer = new ManagedResource(
                12,
                1,
                "statistics-consumer",
                "Statistics Consumer",
                ResourceType.CONSUMER,
                null,
                ResourceStatus.ACTIVE,
                0);
        given(remediations.requestRemediation(new RequestRemediationCommand(KEY, 8, "demo-user")))
                .willReturn(new RequestRemediationResult(
                        new IncidentKey(KEY),
                        IncidentStatus.AWAITING_APPROVAL,
                        9,
                        31,
                        42,
                        53,
                        new ValidatedRemediationProposal(
                                7,
                                "恢复统计消息消费能力",
                                "重新启动已停止的 Statistics Consumer。",
                                "service.restart",
                                consumer,
                                new CapabilitySchema("service.restart.request", 1),
                                "{}",
                                "重新启动 Statistics Consumer",
                                "统计消息消费将在短暂中断后重新启动。",
                                RiskLevel.MEDIUM,
                                true)));

        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/request-remediation")
                        .header(RequestIdFilter.HEADER, "req_remediation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.incidentKey").value(KEY))
                .andExpect(jsonPath("$.data.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.version").value(9))
                .andExpect(jsonPath("$.data.plan.planId").value(31))
                .andExpect(jsonPath("$.data.plan.title").value("恢复统计消息消费能力"))
                .andExpect(jsonPath("$.data.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.action.actionId").value(42))
                .andExpect(jsonPath("$.data.action.capabilityKey").value("service.restart"))
                .andExpect(jsonPath("$.data.action.targetResource.resourceKey").value("statistics-consumer"))
                .andExpect(jsonPath("$.data.action.targetResource.name").value("Statistics Consumer"))
                .andExpect(jsonPath("$.data.action.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.approval.approvalId").value(53))
                .andExpect(jsonPath("$.data.approval.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value("req_remediation-1"));
    }

    /** 05 §29、§31、§94：业务拒绝与 AI 失败映射为规定的 HTTP 状态与错误码。 */
    @ParameterizedTest
    @CsvSource({
        "DIAGNOSIS_NOT_ACTIONABLE, 422",
        "AI_RUNTIME_UNAVAILABLE, 503",
        "AI_OUTPUT_INVALID, 502",
        "AI_RUNTIME_TIMEOUT, 504",
        "INCIDENT_VERSION_CONFLICT, 409",
        "REMEDIATION_ACTION_NOT_EXECUTABLE, 422"
    })
    void requestRemediationFailuresUseTheirCodes(ErrorCode code, int httpStatus) throws Exception {
        given(remediations.requestRemediation(any())).willThrow(new ApplicationException(code, "x", Map.of()));

        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/request-remediation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 8}"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void requestBodiesAreValidatedBeforeAnyUseCase() throws Exception {
        mvc.perform(post("/api/v1/incidents/" + KEY + "/actions/request-remediation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/approvals/53/actions/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedIncidentVersion\": 8, \"riskLevel\": \"LOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        verifyNoInteractions(remediations, approvals);
    }

    /** 05 §37：具体到 Action 与目标资源。 */
    @Test
    void getApprovalShowsTheConcreteAction() throws Exception {
        given(approvals.getApproval(53))
                .willReturn(new ApprovalRepository.ApprovalView(
                        53,
                        ApprovalStatus.PENDING,
                        0,
                        KEY,
                        42,
                        "重新启动 Statistics Consumer",
                        "service.restart",
                        "statistics-consumer",
                        "Statistics Consumer",
                        RiskLevel.MEDIUM,
                        "统计消费短暂中断后恢复。",
                        Instant.parse("2026-09-25T07:31:00Z"),
                        null,
                        null,
                        null));

        mvc.perform(get("/api/v1/approvals/53"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalId").value(53))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.incidentKey").value(KEY))
                .andExpect(jsonPath("$.data.action.actionId").value(42))
                .andExpect(jsonPath("$.data.action.capabilityKey").value("service.restart"))
                .andExpect(jsonPath("$.data.action.targetResource.resourceKey").value("statistics-consumer"))
                .andExpect(jsonPath("$.data.action.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.requestedAt").value("2026-09-25T07:31:00.000Z"));
    }

    /** 05 §41：拒绝 200，返回决定后的 Approval 与 Incident。 */
    @Test
    void rejectReturnsTheDecision() throws Exception {
        given(approvals.reject(new ApprovalDecisionCommand(53, 0, 8, "当前不希望重启消费者。", "demo-user")))
                .willReturn(new ApprovalDecisionResult(
                        53, ApprovalStatus.REJECTED, 1, new IncidentKey(KEY), IncidentStatus.DIAGNOSED, 9));

        mvc.perform(post("/api/v1/approvals/53/actions/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.approvalVersion").value(1))
                .andExpect(jsonPath("$.data.incidentStatus").value("DIAGNOSED"))
                .andExpect(jsonPath("$.data.incidentVersion").value(9));
    }

    /** 05 §38、§43：批准的前置拒绝与已决定、版本冲突各有其码。 */
    @ParameterizedTest
    @CsvSource({
        "approve, RECOVERY_POLICY_NOT_FOUND, 422",
        "approve, REMEDIATION_PLAN_SUPERSEDED, 409",
        "cancel, APPROVAL_ALREADY_DECIDED, 409",
        "reject, APPROVAL_VERSION_CONFLICT, 409",
        "reject, RESOURCE_NOT_FOUND, 404"
    })
    void decisionFailuresUseTheirCodes(String action, ErrorCode code, int httpStatus) throws Exception {
        ApplicationException failure = new ApplicationException(code, "x", Map.of());
        given(approvals.approve(any())).willThrow(failure);
        given(approvals.reject(any())).willThrow(failure);
        given(approvals.cancel(any())).willThrow(failure);

        mvc.perform(post("/api/v1/approvals/53/actions/" + action)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }
}
