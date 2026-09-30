package io.github.ismoyuan.opspilot.web.remediation;

import io.github.ismoyuan.opspilot.application.approval.ApprovalApplicationService;
import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionCommand;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.web.incident.IncidentActionRequest;
import io.github.ismoyuan.opspilot.web.remediation.RemediationResponses.ApprovalDecisionResponse;
import io.github.ismoyuan.opspilot.web.remediation.RemediationResponses.ApprovalResponse;
import io.github.ismoyuan.opspilot.web.remediation.RemediationResponses.RequestRemediationResponse;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处理建议与审批 API（05 §29～§32、§37～§43，08 TASK-065～066）。控制器只做请求映射，不访问持久化；批准在执行与恢复合同接通
 * （TASK-069）前由用例如实拒绝，成功时的 202 合同届时生效。
 */
@RestController
@RequestMapping("/api/v1")
public class RemediationController {

    /** V0.1 单用户 Demo 身份（05 §20）。 */
    static final String DEMO_USER = "demo-user";

    private final RemediationApplicationService remediations;
    private final ApprovalApplicationService approvals;

    public RemediationController(RemediationApplicationService remediations, ApprovalApplicationService approvals) {
        this.remediations = remediations;
        this.approvals = approvals;
    }

    @PostMapping("/incidents/{incidentKey}/actions/request-remediation")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RequestRemediationResponse> requestRemediation(
            @PathVariable String incidentKey,
            @Valid @RequestBody IncidentActionRequest body,
            HttpServletRequest request) {
        return respond(
                RequestRemediationResponse.of(remediations.requestRemediation(
                        new RequestRemediationCommand(incidentKey, body.expectedVersion(), DEMO_USER))),
                request);
    }

    @GetMapping("/approvals/{approvalId}")
    public ApiResponse<ApprovalResponse> approval(@PathVariable long approvalId, HttpServletRequest request) {
        return respond(ApprovalResponse.of(approvals.getApproval(approvalId)), request);
    }

    @PostMapping("/approvals/{approvalId}/actions/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ApprovalDecisionResponse> approve(
            @PathVariable long approvalId,
            @Valid @RequestBody ApprovalDecisionRequest body,
            HttpServletRequest request) {
        return respond(ApprovalDecisionResponse.of(approvals.approve(command(approvalId, body))), request);
    }

    @PostMapping("/approvals/{approvalId}/actions/reject")
    public ApiResponse<ApprovalDecisionResponse> reject(
            @PathVariable long approvalId,
            @Valid @RequestBody ApprovalDecisionRequest body,
            HttpServletRequest request) {
        return respond(ApprovalDecisionResponse.of(approvals.reject(command(approvalId, body))), request);
    }

    @PostMapping("/approvals/{approvalId}/actions/cancel")
    public ApiResponse<ApprovalDecisionResponse> cancel(
            @PathVariable long approvalId,
            @Valid @RequestBody ApprovalDecisionRequest body,
            HttpServletRequest request) {
        return respond(ApprovalDecisionResponse.of(approvals.cancel(command(approvalId, body))), request);
    }

    private static ApprovalDecisionCommand command(long approvalId, ApprovalDecisionRequest body) {
        return new ApprovalDecisionCommand(
                approvalId, body.expectedApprovalVersion(), body.expectedIncidentVersion(), body.comment(), DEMO_USER);
    }

    private static <T> ApiResponse<T> respond(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, RequestIdFilter.requestId(request));
    }
}
