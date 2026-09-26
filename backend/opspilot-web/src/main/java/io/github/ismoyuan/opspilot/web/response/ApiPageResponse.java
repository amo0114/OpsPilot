package io.github.ismoyuan.opspilot.web.response;

import io.github.ismoyuan.opspilot.application.query.PageResult;
import java.util.List;

/** 05 §8 列表成功响应。 */
public record ApiPageResponse<T>(List<T> data, Page page, String requestId) {

    public record Page(int number, int size, long totalElements, long totalPages) {}

    public static <T> ApiPageResponse<T> of(PageResult<T> result, String requestId) {
        return new ApiPageResponse<>(
                result.items(),
                new Page(result.number(), result.size(), result.totalElements(), result.totalPages()),
                requestId);
    }
}
