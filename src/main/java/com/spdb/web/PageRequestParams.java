package com.spdb.web;

import java.util.Set;

public record PageRequestParams(int page, int size) {
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final Set<Integer> SUPPORTED_SIZES = Set.of(20, 50, 100, 200);

    public static PageRequestParams of(Integer page, Integer size) {
        int safePage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int safeSize = size == null || !SUPPORTED_SIZES.contains(size) ? DEFAULT_SIZE : size;
        return new PageRequestParams(safePage, safeSize);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public int totalPages(long total) {
        if (total <= 0) {
            return 1;
        }
        return (int) Math.ceil(total / (double) size);
    }

    public int[] allowedSizes() {
        return new int[]{20, 50, 100, 200};
    }
}
