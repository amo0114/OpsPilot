package io.github.ismoyuan.opspilot.web.incident;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * 05 §20 请求体。文本长度、startedAt 与资源归属由创建用例统一校验（NewIncident 与 V002 同源），此处只要求能定位系统。
 *
 * @param description 可为空
 * @param startedAt 可为空
 * @param affectedResourceKeys 可为空
 */
public record CreateIncidentRequest(
        @NotBlank String systemKey,
        String title,
        String description,
        String impactSummary,
        Instant startedAt,
        List<String> affectedResourceKeys) {}
