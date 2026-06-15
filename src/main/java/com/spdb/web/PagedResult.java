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
        int totalPages = params.totalPages(total);
        int displayPage = Math.min(params.page(), totalPages);
        return new PagedResult<>(rows, total, displayPage, params.size(), totalPages, params.allowedSizes());
    }
}
