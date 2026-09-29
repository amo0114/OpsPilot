package io.github.ismoyuan.opspilot.application.capability.raw;

import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedText;

/**
 * 较大 Provider 原始结果的存放端口（08 TASK-050、04 §92、07 §94～§95）：只接受已脱敏文本（06 §32，raw_result_ref 不是未脱敏数据的后门），
 * 返回写入 capability_invocation.raw_result_ref 的 {@code file://} 引用。V0.1 实现为本地目录；可替换实现，不改变业务合同。
 *
 * <p>写入发生在 Provider 返回之后、结果事务之前，不在任何数据库事务内。
 */
public interface RawResultStore {

    /**
     * 保存一次调用的已脱敏原始结果；每次保存都是新内容，不覆盖已有内容。
     *
     * @return {@code file://} 引用，不超过 512 字符
     * @throws java.io.UncheckedIOException 无法写入
     * @throws IllegalStateException 引用会超过 512 字符
     */
    String store(long incidentId, long invocationId, SanitizedText content);

    /**
     * 读回 {@link #store} 保存的内容。
     *
     * @throws IllegalArgumentException 引用不是本存储根目录内的 {@code file://} 引用
     * @throws java.io.UncheckedIOException 无法读取
     */
    String read(String ref);
}
