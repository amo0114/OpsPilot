package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import java.util.Map;

/** 按对外编号加锁读取 Incident；格式不合法的编号与不存在同样返回 INCIDENT_NOT_FOUND。 */
public final class IncidentLocks {

    private IncidentLocks() {}

    public static Incident lockByKey(IncidentRepository incidents, String incidentKey) {
        IncidentKey key;
        try {
            key = new IncidentKey(incidentKey);
        } catch (RuntimeException ex) {
            throw notFound(incidentKey);
        }
        return incidents.findByKeyForUpdate(key).orElseThrow(() -> notFound(incidentKey));
    }

    public static ApplicationException notFound(String incidentKey) {
        return new ApplicationException(
                ErrorCode.INCIDENT_NOT_FOUND, "Incident not found", Map.of("incidentKey", String.valueOf(incidentKey)));
    }
}
