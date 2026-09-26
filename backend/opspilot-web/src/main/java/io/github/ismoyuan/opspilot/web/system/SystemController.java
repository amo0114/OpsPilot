package io.github.ismoyuan.opspilot.web.system;

import io.github.ismoyuan.opspilot.application.system.query.ResourceDetailView;
import io.github.ismoyuan.opspilot.application.system.query.SystemDetailView;
import io.github.ismoyuan.opspilot.application.system.query.SystemQueryService;
import io.github.ismoyuan.opspilot.application.system.query.SystemSummaryView;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiPageResponse;
import io.github.ismoyuan.opspilot.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 业务系统只读 API（05 §14～§17）；系统配置由 Seed 建立，不提供写入。 */
@RestController
@RequestMapping("/api/v1/systems")
@Tag(name = "Systems")
public class SystemController {

    private final SystemQueryService systems;

    public SystemController(SystemQueryService systems) {
        this.systems = systems;
    }

    @GetMapping
    public ApiPageResponse<SystemSummaryView> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiPageResponse.of(systems.listSystems(page, size), RequestIdFilter.requestId(request));
    }

    @GetMapping("/{systemKey}")
    public ApiResponse<SystemDetailView> get(@PathVariable String systemKey, HttpServletRequest request) {
        return new ApiResponse<>(systems.getSystem(systemKey), RequestIdFilter.requestId(request));
    }

    @GetMapping("/{systemKey}/resources/{resourceKey}")
    public ApiResponse<ResourceDetailView> getResource(
            @PathVariable String systemKey, @PathVariable String resourceKey, HttpServletRequest request) {
        return new ApiResponse<>(systems.getResource(systemKey, resourceKey), RequestIdFilter.requestId(request));
    }
}
