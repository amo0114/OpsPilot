package io.github.ismoyuan.opspilot.application.investigation.context;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityDescriptor;
import java.util.List;

/**
 * 该 Incident 可供 AI 选择的 OBSERVE 能力描述（06 §22）：只来自受信配置，未绑定、禁用、归属错误或 Provider 不唯一的能力不出现。
 * 真实实现由 Capability Registry 提供（TASK-044/045）。
 */
public interface CapabilityDescriptorSource {

    List<CapabilityDescriptor> describe(long incidentId);
}
