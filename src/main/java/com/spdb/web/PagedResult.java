package com.spdb.web;

import java.util.List;

public record PagedResult<T>(
        List<T> rows,
        long total,
        int page,
        int size,
        int totalPages,
        int[] allowedSizes
) {
    public static <T> PagedResult<T> of(List<T> rows, long total, PageRequestParams params) {
        return new PagedResult<>(rows, total, params.page(), params.size(), params.totalPages(total), params.allowedSizes());
    }
}
