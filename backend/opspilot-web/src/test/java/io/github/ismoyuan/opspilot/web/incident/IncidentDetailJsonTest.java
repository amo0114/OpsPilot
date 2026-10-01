package io.github.ismoyuan.opspilot.web.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentDetailView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.CriterionReason;
import io.github.ismoyuan.opspilot.application.recovery.CriterionResult;
import io.github.ismoyuan.opspilot.application.recovery.ProjectedValue;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySample;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusView;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 故障详情恢复样本值的 JSON 表示（B31-R1 P3、00 §26 “2180 → 940 → 210 → 8”）：整数值输出为 JSON 整数而不是 2180.0，含小数的值保持
 * 小数，超出 long 的值按小数输出（含 ±2^63 两侧的相邻 double：2^63 不是 long，-2^63 是 Long.MIN_VALUE）；取不到可靠值时 value 为 null 并给出原因。数值本身不变，只验证表示。
 */
@WebMvcTest(IncidentController.class)
class IncidentDetailJsonTest {

    private static final String KEY = "INC-20261001-0001";
    private static final Instant AT = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IncidentQueryService queries;

    @MockitoBean
    IncidentApplicationService incidents;

    @MockitoBean
    InvestigationApplicationService investigations;

    @Test
    void sampleValuesKeepIntegersIntegralAndDecimalsDecimal() throws Exception {
        given(queries.getIncident(KEY))
                .willReturn(detail(List.of(
                        sample(1, new ProjectedValue.Number(2180)),
                        sample(2, new ProjectedValue.Number(12.5)),
                        sample(3, new ProjectedValue.Number(0)),
                        sample(4, new ProjectedValue.Number(1.0e19)),
                        sample(5, new ProjectedValue.Text("RUNNING")),
                        sample(6, new ProjectedValue.Unknown(ProjectedValue.Unknown.GROUP_MISSING)),
                        // long 边界（B32 后 Review P2）：2^63 不能截断成 Long.MAX_VALUE 后误判为整数
                        sample(7, new ProjectedValue.Number(0x1.0p63)),
                        sample(8, new ProjectedValue.Number(Math.nextDown(0x1.0p63))),
                        sample(9, new ProjectedValue.Number(-0x1.0p63)),
                        sample(10, new ProjectedValue.Number(Math.nextDown(-0x1.0p63))),
                        sample(11, new ProjectedValue.Number(Math.nextUp(-0x1.0p63))))));

        String body = mvc.perform(get("/api/v1/incidents/" + KEY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains("\"sampleIndex\":1,\"status\":\"SUCCEEDED\",\"sampledAt\":\"2026-10-01T08:00:00.000Z\","
                        + "\"value\":2180,")
                .doesNotContain("2180.0")
                .contains("\"value\":12.5,")
                .contains("\"sampleIndex\":3,\"status\":\"SUCCEEDED\",\"sampledAt\":\"2026-10-01T08:00:00.000Z\","
                        + "\"value\":0,")
                .contains("\"value\":1.0E19,")
                .contains("\"value\":\"RUNNING\",")
                .contains("\"value\":null,\"valueUnknownCause\":\"GROUP_MISSING\"")
                .contains("\"value\":9.223372036854776E18,")
                .doesNotContain("9223372036854775807")
                .contains("\"value\":9223372036854774784,")
                .contains("\"value\":-9223372036854775808,")
                .contains("\"value\":-9.223372036854778E18,")
                .contains("\"value\":-9223372036854774784,");
    }

    private static RecoveryStatusView.Sample sample(int index, ProjectedValue value) {
        return new RecoveryStatusView.Sample(index, RecoverySample.Status.SUCCEEDED, AT, value, null);
    }

    private static IncidentDetailView detail(List<RecoveryStatusView.Sample> samples) {
        RecoveryStatusView recovery = new RecoveryStatusView(
                1,
                RecoveryVerificationStatus.PASSED,
                "恢复验证通过",
                "statistics-consumer",
                "统计消费者",
                "consumer-recovery",
                "统计消费者恢复标准",
                1,
                true,
                AT,
                AT,
                AT,
                List.of(new RecoveryStatusView.Check(
                        "stream-lag-drained", "积压达标", true, CriterionResult.TRUE, CriterionReason.SATISFIED, samples)));
        return new IncidentDetailView(
                KEY,
                "统计积压",
                null,
                "shortlink-platform",
                "ShortLink Platform",
                IncidentStatus.RESOLVED,
                12,
                40,
                "统计延迟",
                AT,
                AT,
                AT,
                List.of(),
                null,
                null,
                null,
                recovery,
                List.of());
    }
}
