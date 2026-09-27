package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;

/** 分配的编号已被并发创建占用（uk_incident_key）；创建用例据此在新事务中重新分配。 */
public class IncidentKeyTakenException extends ApplicationException {

    public IncidentKeyTakenException(String incidentKey) {
        super(ErrorCode.INTERNAL_ERROR, "Incident key already taken: " + incidentKey, Map.of());
    }
}
