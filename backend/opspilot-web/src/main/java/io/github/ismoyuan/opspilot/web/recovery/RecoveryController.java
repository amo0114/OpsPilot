package io.github.ismoyuan.opspilot.web.recovery;

import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryCommand;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryResult;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部处理后的恢复验证入口（05 §34～§35、§91，08 TASK-081）。控制器只做请求映射；创建事务提交即返回 202，不等待采样，结果由后续
 * 查询与 SSE 获取。
 */
@RestController
@RequestMapping("/api/v1")
public class RecoveryController {

    /** V0.1 单用户 Demo 身份（05 §20）。 */
    static final String DEMO_USER = "demo-user";

    private final RecoveryVerificationApplicationService verifications;

    public RecoveryController(RecoveryVerificationApplicationService verifications) {
        this.verifications = verifications;
    }

    @PostMapping("/incidents/{incidentKey}/actions/verify-recovery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VerifyRecoveryResponse> verifyRecovery(
            @PathVariable String incidentKey,
            @Valid @RequestBody VerifyRecoveryRequest body,
            HttpServletRequest request) {
        VerifyRecoveryResult result = verifications.verifyRecovery(new VerifyRecoveryCommand(
                incidentKey, body.expectedVersion(), body.resourceKey(), body.note(), DEMO_USER));
        return new ApiResponse<>(VerifyRecoveryResponse.of(result), RequestIdFilter.requestId(request));
    }

    /** 05 §34：新 Verification 的编号与 PENDING 状态，以及迁移后的 Incident 版本，用于后续 GET。 */
    public record VerifyRecoveryResponse(
            String incidentKey,
            IncidentStatus incidentStatus,
            long incidentVersion,
            int verificationNo,
            RecoveryVerificationStatus verificationStatus) {

        static VerifyRecoveryResponse of(VerifyRecoveryResult result) {
            return new VerifyRecoveryResponse(
                    result.incidentKey().value(),
                    result.incidentStatus(),
                    result.incidentVersion(),
                    result.verificationNo(),
                    result.verificationStatus());
        }
    }
}
