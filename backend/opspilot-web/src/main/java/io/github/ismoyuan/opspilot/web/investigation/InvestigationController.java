package io.github.ismoyuan.opspilot.web.investigation;

import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationQueryService;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationFilter;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.DiagnosisDetailResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.DiagnosisSummaryResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.EvidenceResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.HypothesisResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.ObservationDetailResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.ObservationSummaryResponse;
import io.github.ismoyuan.opspilot.web.investigation.InvestigationResponses.OverviewResponse;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiPageResponse;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调查技术详情 API（05 §49～§56、08 TASK-027），全部只读：没有 POST/PUT Hypothesis、Evidence 或 Diagnosis（05 §51、§54）。
 * Controller 只做 HTTP↔View 映射，不访问 Mapper。
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentKey}")
@Tag(name = "Investigation")
public class InvestigationController {

    private final InvestigationQueryService queries;

    public InvestigationController(InvestigationQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/investigation")
    public ApiResponse<OverviewResponse> overview(@PathVariable String incidentKey, HttpServletRequest request) {
        return respond(OverviewResponse.of(queries.getOverview(incidentKey)), request);
    }

    @GetMapping("/investigation/hypotheses")
    public ApiResponse<List<HypothesisResponse>> hypotheses(
            @PathVariable String incidentKey, HttpServletRequest request) {
        return respond(
                queries.listHypotheses(incidentKey).stream()
                        .map(HypothesisResponse::of)
                        .toList(),
                request);
    }

    @GetMapping("/investigation/observations")
    public ApiPageResponse<ObservationSummaryResponse> observations(
            @PathVariable String incidentKey,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) ObservationKind kind,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        var result = queries.listObservations(incidentKey, new ObservationFilter(resourceKey, kind), page, size);
        return ApiPageResponse.of(result.map(ObservationSummaryResponse::of), RequestIdFilter.requestId(request));
    }

    @GetMapping("/investigation/observations/{observationId}")
    public ApiResponse<ObservationDetailResponse> observation(
            @PathVariable String incidentKey, @PathVariable long observationId, HttpServletRequest request) {
        return respond(ObservationDetailResponse.of(queries.getObservation(incidentKey, observationId)), request);
    }

    @GetMapping("/investigation/evidence")
    public ApiResponse<List<EvidenceResponse>> evidence(@PathVariable String incidentKey, HttpServletRequest request) {
        return respond(
                queries.listEvidence(incidentKey).stream()
                        .map(EvidenceResponse::of)
                        .toList(),
                request);
    }

    @GetMapping("/diagnoses")
    public ApiResponse<List<DiagnosisSummaryResponse>> diagnoses(
            @PathVariable String incidentKey, HttpServletRequest request) {
        return respond(
                queries.listDiagnoses(incidentKey).stream()
                        .map(DiagnosisSummaryResponse::of)
                        .toList(),
                request);
    }

    @GetMapping("/diagnoses/{version}")
    public ApiResponse<DiagnosisDetailResponse> diagnosis(
            @PathVariable String incidentKey, @PathVariable @Min(1) int version, HttpServletRequest request) {
        return respond(DiagnosisDetailResponse.of(queries.getDiagnosis(incidentKey, version)), request);
    }

    private static <T> ApiResponse<T> respond(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, RequestIdFilter.requestId(request));
    }
}
