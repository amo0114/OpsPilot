package io.github.ismoyuan.opspilot.application.capability;

import java.util.Objects;

/**
 * 准入时由 windowKey 解析出的查询窗口（06 §42）；Provider 按它执行，不再自行解析。
 *
 * @param previous 请求前一窗口比较时给出：与 current 等长、紧邻（previous.end = current.start）；否则为空
 */
public record ResolvedWindow(QueryWindow current, QueryWindow previous) {

    public ResolvedWindow {
        Objects.requireNonNull(current, "current");
        if (previous != null
                && (!previous.end().equals(current.start())
                        || !previous.length().equals(current.length()))) {
            throw new IllegalArgumentException("previous window must be adjacent and of equal length");
        }
    }
}
