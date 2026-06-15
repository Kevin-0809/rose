package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PagedResultTest {

    @Test
    void clampsDisplayedPageToTotalPages() {
        PagedResult<String> result = PagedResult.of(List.of(), 7, PageRequestParams.of(2, 20));

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }
}
