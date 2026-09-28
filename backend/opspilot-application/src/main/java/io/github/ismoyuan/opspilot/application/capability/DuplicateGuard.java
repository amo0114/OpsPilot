package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.canonical.CanonicalJsonWriter;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 调查调用的 Duplicate Guard（08 TASK-047、06 §124、07 §57）。指纹 = investigationId＋capabilityKey＋resourceId＋请求 Schema 名/版本＋
 * canonical(arguments)，不含 run_no，因此 Continue 不能绕过刚完成的同一请求。范围是同指纹的全部在途调用，以及 finished_at 距今小于
 * 保护窗口的终态调用——按 finished_at 而非 created_at，由数据库按状态与时间筛选，不假设历史条数。必须在准入事务内、持有
 * Investigation 行锁时调用；恢复采样按样本身份调度，不经过本 Guard。
 */
@Service
public class DuplicateGuard {

    private final CapabilityInvocationRepository invocations;
    private final CanonicalJsonWriter canonicalJson;
    private final CapabilityGuardSettings settings;

    public DuplicateGuard(
            CapabilityInvocationRepository invocations,
            CanonicalJsonWriter canonicalJson,
            CapabilityGuardSettings settings) {
        this.invocations = invocations;
        this.canonicalJson = canonicalJson;
        this.settings = settings;
    }

    /** @param canonicalArguments 参数的规范 JSON（{@link CanonicalJsonWriter#write}） */
    public boolean isDuplicate(
            long investigationId,
            String capabilityKey,
            long resourceId,
            CapabilitySchema requestSchema,
            String canonicalArguments,
            Instant now) {
        return invocations
                .findGuardedRequestPayloads(
                        investigationId,
                        capabilityKey,
                        resourceId,
                        requestSchema,
                        now.minus(settings.duplicateWindow()))
                .stream()
                .map(canonicalJson::canonicalize)
                .anyMatch(canonicalArguments::equals);
    }
}
