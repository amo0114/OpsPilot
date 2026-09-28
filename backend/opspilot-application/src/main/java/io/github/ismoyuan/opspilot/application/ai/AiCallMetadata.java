package io.github.ismoyuan.opspilot.application.ai;

/**
 * 一次 AI 调用的技术元数据，随结果记入 AgentStep（04 §59、08 TASK-038）；由 AI Runtime 以响应头提供，不属于 v1 协议体。
 * 各字段可为空：Runtime 未报告或取值超出记录列长度时不记录。
 */
public record AiCallMetadata(
        String modelProvider,
        String modelName,
        String promptTemplateVersion,
        Integer promptTokens,
        Integer completionTokens) {

    public static final AiCallMetadata UNKNOWN = new AiCallMetadata(null, null, null, null, null);
}
