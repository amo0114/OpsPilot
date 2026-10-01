package io.github.ismoyuan.opspilot.web.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultLabApplicationService;
import io.github.ismoyuan.opspilot.application.faultlab.FaultScenario;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultCommand;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultResult;
import io.github.ismoyuan.opspilot.application.faultlab.ResetFaultResult;
import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障实验室 API（05 §68～§72、08 TASK-092）：只对 DEMO/TEST 环境的系统生效，判断在用例中。响应不含 Ground Truth；没有 Ground Truth 的读取
 * 接口（05 §73）。Reset 只恢复实验环境，不改变 Incident。
 */
@RestController
@RequestMapping("/api/v1/fault-lab")
@Tag(name = "Fault Lab")
public class FaultLabController {

    /** V0.1 单用户 Demo 身份（05 §20）。 */
    static final String DEMO_USER = "demo-user";

    private final FaultLabApplicationService faultLab;

    public FaultLabController(FaultLabApplicationService faultLab) {
        this.faultLab = faultLab;
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioResponse>> scenarios(HttpServletRequest request) {
        return respond(
                faultLab.listScenarios().stream().map(ScenarioResponse::of).toList(), request);
    }

    @PostMapping("/scenarios/{scenarioKey}/actions/inject")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InjectResponse> inject(
            @PathVariable String scenarioKey, @Valid @RequestBody InjectRequest body, HttpServletRequest request) {
        return respond(
                InjectResponse.of(faultLab.inject(new InjectFaultCommand(scenarioKey, body.systemKey(), DEMO_USER))),
                request);
    }

    @PostMapping("/experiments/{experimentId}/actions/reset")
    public ApiResponse<ResetResponse> reset(@PathVariable long experimentId, HttpServletRequest request) {
        ResetFaultResult result = faultLab.reset(experimentId);
        return respond(new ResetResponse(result.experimentId(), result.status()), request);
    }

    private static <T> ApiResponse<T> respond(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, RequestIdFilter.requestId(request));
    }

    /** 05 §70 请求体。 */
    public record InjectRequest(@NotBlank String systemKey) {}

    /** 05 §69：场景说明与目标资源；不返回 Ground Truth 或 Incident 模板。 */
    public record ScenarioResponse(String scenarioKey, String name, String description, String targetResourceKey) {

        static ScenarioResponse of(FaultScenario scenario) {
            return new ScenarioResponse(
                    scenario.scenarioKey(), scenario.name(), scenario.description(), scenario.targetResourceKey());
        }
    }

    /** 05 §70：已确认生效的实验与同事务创建的 CREATED Incident。 */
    public record InjectResponse(long experimentId, FaultExperimentStatus status, IncidentRef incident) {

        public record IncidentRef(
                String incidentKey, IncidentStatus status, long version, List<IncidentAction> availableActions) {}

        static InjectResponse of(InjectFaultResult result) {
            var incident = result.incident();
            return new InjectResponse(
                    result.experimentId(),
                    result.status(),
                    new IncidentRef(
                            incident.incidentKey().value(),
                            incident.status(),
                            incident.version(),
                            incident.availableActions()));
        }
    }

    /** 05 §72：实验环境已恢复；不表示 Incident 已恢复。 */
    public record ResetResponse(long experimentId, FaultExperimentStatus status) {}
}
