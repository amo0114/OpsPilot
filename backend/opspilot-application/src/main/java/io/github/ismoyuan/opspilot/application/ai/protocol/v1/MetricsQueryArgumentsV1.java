package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * metrics.query 参数（06 §41～§42）：语义指标名、受控窗口与是否比较前一等长窗口；不接受 PromQL。
 *
 * @param comparePreviousWindow 必须显式给出
 */
public record MetricsQueryArgumentsV1(String metricKey, WindowKey windowKey, Boolean comparePreviousWindow)
        implements CapabilityArguments {

    public MetricsQueryArgumentsV1 {
        ProtocolChecks.metricKey(metricKey);
        ProtocolChecks.required("windowKey", windowKey);
        ProtocolChecks.required("comparePreviousWindow", comparePreviousWindow);
    }
}
