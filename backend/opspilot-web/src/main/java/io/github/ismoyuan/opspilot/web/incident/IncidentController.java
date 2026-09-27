package io.github.ismoyuan.opspilot.web.incident;

import io.github.ismoyuan.opspilot.application.incident.CancelIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.CancelIncidentResult;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentResult;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentFilter;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryService;
import io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRunResult;
import io.github.ismoyuan.opspilot.application.investigation.StartInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.StopInvestigationCommand;
import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.web.incident.IncidentResponses.IncidentDetailResponse;
import io.github.ismoyuan.opspilot.web.incident.IncidentResponses.IncidentStateResponse;
import io.github.ismoyuan.opspilot.web.incident.IncidentResponses.IncidentSummaryResponse;
import io.github.ismoyuan.opspilot.web.incident.IncidentResponses.InvestigationRunResponse;
import io.github.ismoyuan.opspilot.web.incident.IncidentResponses.StartInvestigationResponse;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiPageResponse;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident 基础 API（05 §20～§28、§33、08 TASK-020）。公开身份只用 incidentKey；Controller 只做 HTTP↔Command/View 映射，
 * 状态与版本规则全部在用例事务中。202 只表示已接受并落账，不代表后台调查已完成。
 */
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents")
public class IncidentController {

    /** V0.1 单用户 Demo 身份（05 §20）。 */
    static final String DEMO_USER = "demo-user";

    private final IncidentApplicationService incidents;
    private final InvestigationApplicationService investigations;
    private final IncidentQueryService queries;

    public IncidentController(
            IncidentApplicationService incidents,
            InvestigationApplicationService investigations,
            IncidentQueryService queries) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IncidentStateResponse> create(
            @Valid @RequestBody CreateIncidentRequest body, HttpServletRequest request) {
        CreateIncidentResult result = incidents.createIncident(new CreateIncidentCommand(
                body.systemKey(),
                body.title(),
                body.description(),
                body.impactSummary(),
                body.startedAt(),
                body.affectedResourceKeys(),
                IncidentSource.MANUAL,
                DEMO_USER));
        return respond(
                new IncidentStateResponse(result.incidentKey().value(), result.status(), result.version()), request);
    }

    @GetMapping
    public ApiPageResponse<IncidentSummaryResponse> list(
            @RequestParam(required = false) String systemKey,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        var result = queries.listIncidents(new IncidentFilter(systemKey, status), page, size);
        return ApiPageResponse.of(result.map(IncidentSummaryResponse::of), RequestIdFilter.requestId(request));
    }

    @GetMapping("/{incidentKey}")
    public ApiResponse<IncidentDetailResponse> get(@PathVariable String incidentKey, HttpServletRequest request) {
        return respond(IncidentDetailResponse.of(queries.getIncident(incidentKey)), request);
    }

    @PostMapping("/{incidentKey}/actions/start-investigation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<StartInvestigationResponse> startInvestigation(
            @PathVariable String incidentKey,
            @Valid @RequestBody IncidentActionRequest body,
            HttpServletRequest request) {
        InvestigationRunResult result = investigations.startInvestigation(
                new StartInvestigationCommand(incidentKey, body.expectedVersion(), DEMO_USER));
        return respond(
                new StartInvestigationResponse(result.incidentKey().value(), result.status(), result.version(), true),
                request);
    }

    @PostMapping("/{incidentKey}/actions/continue-investigation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InvestigationRunResponse> continueInvestigation(
            @PathVariable String incidentKey,
            @Valid @RequestBody IncidentActionRequest body,
            HttpServletRequest request) {
        return respond(
                runResponse(investigations.continueInvestigation(
                        new ContinueInvestigationCommand(incidentKey, body.expectedVersion(), DEMO_USER))),
                request);
    }

    @PostMapping("/{incidentKey}/actions/stop-investigation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InvestigationRunResponse> stopInvestigation(
            @PathVariable String incidentKey,
            @Valid @RequestBody IncidentActionRequest body,
            HttpServletRequest request) {
        return respond(
                runResponse(investigations.stopInvestigation(
                        new StopInvestigationCommand(incidentKey, body.expectedVersion(), DEMO_USER))),
                request);
    }

    @PostMapping("/{incidentKey}/actions/cancel")
    public ApiResponse<IncidentStateResponse> cancel(
            @PathVariable String incidentKey,
            @Valid @RequestBody CancelIncidentRequest body,
            HttpServletRequest request) {
        CancelIncidentResult result = incidents.cancelIncident(
                new CancelIncidentCommand(incidentKey, body.expectedVersion(), body.reason(), DEMO_USER));
        return respond(
                new IncidentStateResponse(result.incidentKey().value(), result.status(), result.version()), request);
    }

    private static InvestigationRunResponse runResponse(InvestigationRunResult result) {
        return new InvestigationRunResponse(
                result.incidentKey().value(),
                result.status(),
                result.version(),
                result.runNo(),
                result.stopRequested());
    }

    private static <T> ApiResponse<T> respond(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, RequestIdFilter.requestId(request));
    }
}
