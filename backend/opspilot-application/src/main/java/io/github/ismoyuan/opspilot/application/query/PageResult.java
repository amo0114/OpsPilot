package io.github.ismoyuan.opspilot.application.query;

import java.util.List;

/**
 * 分页查询结果（05 §8）。
 *
 * @param number 从 0 开始的页码
 * @param size 请求的每页条数
 */
public record PageResult<T>(List<T> items, int number, int size, long totalElements) {

    public PageResult {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return (totalElements + size - 1) / size;
    }
}
