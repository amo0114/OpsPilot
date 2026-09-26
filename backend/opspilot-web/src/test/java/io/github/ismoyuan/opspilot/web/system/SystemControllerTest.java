package io.github.ismoyuan.opspilot.web.system;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ismoyuan.opspilot.application.system.query.ResourceCapabilityProjection;
import io.github.ismoyuan.opspilot.application.system.query.ResourceSummaryView;
import io.github.ismoyuan.opspilot.application.system.query.SystemDetailView;
import io.github.ismoyuan.opspilot.application.system.query.SystemQueryRepository;
import io.github.ismoyuan.opspilot.application.system.query.SystemQueryService;
import io.github.ismoyuan.opspilot.application.system.query.SystemSummaryView;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 05 §8、§15～§17：Systems API 的响应包络、分页、404 错误码与能力 mode 映射；仓储以桩替代。 */
@WebMvcTest(SystemController.class)
@Import(SystemQueryService.class)
class SystemControllerTest {

    private static final ResourceSummaryView CONSUMER = new ResourceSummaryView(
            "statistics-consumer", "Statistics Consumer", ResourceType.CONSUMER, ResourceStatus.ACTIVE);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SystemQueryRepository repository;

    @Test
    void listUsesPageEnvelope() throws Exception {
        given(repository.countSystems()).willReturn(3L);
        given(repository.findSystems(2, 2))
                .willReturn(List.of(new SystemSummaryView(
                        "shortlink-platform", "ShortLink Platform", "DEMO", SystemStatus.ACTIVE, 5)));

        mvc.perform(get("/api/v1/systems?page=1&size=2").header(RequestIdFilter.HEADER, "req_list-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].systemKey").value("shortlink-platform"))
                .andExpect(jsonPath("$.data[0].name").value("ShortLink Platform"))
                .andExpect(jsonPath("$.data[0].environment").value("DEMO"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].resourceCount").value(5))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.requestId").value("req_list-1"));
    }

    @Test
    void listDefaultsToFirstPageOfTwenty() throws Exception {
        given(repository.countSystems()).willReturn(0L);
        given(repository.findSystems(0, 20)).willReturn(List.of());

        mvc.perform(get("/api/v1/systems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "size=0", "size=101", "page=abc"})
    void invalidPagingIsRejectedBeforeQuerying(String query) throws Exception {
        mvc.perform(get("/api/v1/systems?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        verifyNoInteractions(repository);
    }

    @Test
    void systemDetailReturnsResources() throws Exception {
        given(repository.findSystemDetail("shortlink-platform"))
                .willReturn(Optional.of(new SystemDetailView(
                        "shortlink-platform",
                        "ShortLink Platform",
                        "短链接业务系统",
                        "DEMO",
                        SystemStatus.ACTIVE,
                        List.of(CONSUMER))));

        mvc.perform(get("/api/v1/systems/shortlink-platform").header(RequestIdFilter.HEADER, "req_detail-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemKey").value("shortlink-platform"))
                .andExpect(jsonPath("$.data.description").value("短链接业务系统"))
                .andExpect(jsonPath("$.data.resources[0].resourceKey").value("statistics-consumer"))
                .andExpect(jsonPath("$.data.resources[0].resourceType").value("CONSUMER"))
                .andExpect(jsonPath("$.data.resources[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.requestId").value("req_detail-1"))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void unknownSystemIsSystemNotFound() throws Exception {
        given(repository.findSystemDetail(anyString())).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/systems/missing-platform"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYSTEM_NOT_FOUND"))
                .andExpect(jsonPath("$.details.systemKey").value("missing-platform"));
    }

    @Test
    void resourceDetailMapsModesAndDropsUnknownCapabilities() throws Exception {
        given(repository.findResource("shortlink-platform", "statistics-consumer"))
                .willReturn(Optional.of(new ResourceCapabilityProjection(
                        CONSUMER, List.of("custom.thing", "service.inspect", "service.restart"))));

        mvc.perform(get("/api/v1/systems/shortlink-platform/resources/statistics-consumer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceKey").value("statistics-consumer"))
                .andExpect(jsonPath("$.data.name").value("Statistics Consumer"))
                .andExpect(jsonPath("$.data.resourceType").value("CONSUMER"))
                .andExpect(jsonPath("$.data.capabilities.length()").value(2))
                .andExpect(jsonPath("$.data.capabilities[0].key").value("service.inspect"))
                .andExpect(jsonPath("$.data.capabilities[0].mode").value("OBSERVE"))
                .andExpect(jsonPath("$.data.capabilities[1].key").value("service.restart"))
                .andExpect(jsonPath("$.data.capabilities[1].mode").value("CHANGE"))
                .andExpect(jsonPath("$.data.recoveryPolicy").isEmpty())
                .andExpect(content().string(not(containsString("custom.thing"))));
    }

    @Test
    void missingResourceDistinguishesSystemFromResource() throws Exception {
        given(repository.findResource(anyString(), anyString())).willReturn(Optional.empty());
        given(repository.systemExists("shortlink-platform")).willReturn(true);
        given(repository.systemExists("missing-platform")).willReturn(false);

        mvc.perform(get("/api/v1/systems/shortlink-platform/resources/missing-service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.details.systemKey").value("shortlink-platform"))
                .andExpect(jsonPath("$.details.resourceKey").value("missing-service"));
        mvc.perform(get("/api/v1/systems/missing-platform/resources/statistics-consumer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYSTEM_NOT_FOUND"));
    }
}
