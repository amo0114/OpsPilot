package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * database.inspect 参数（06 §70～§72）：固定检查类型与可选条数；不接受 SQL 文本。
 *
 * @param limit 可缺省，给出时 1～20
 */
public record DatabaseInspectArgumentsV1(InspectionType inspectionType, Integer limit) implements CapabilityArguments {

    public static final int LIMIT_MIN = 1;
    public static final int LIMIT_MAX = 20;

    public DatabaseInspectArgumentsV1 {
        ProtocolChecks.required("inspectionType", inspectionType);
        if (limit != null && (limit < LIMIT_MIN || limit > LIMIT_MAX)) {
            throw ProtocolChecks.invalid("limit");
        }
    }
}
