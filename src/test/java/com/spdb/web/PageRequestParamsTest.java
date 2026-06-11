package com.spdb.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageRequestParamsTest {

    @Test
    void defaultsToFirstPageAndTwentyRows() {
        PageRequestParams params = PageRequestParams.of(null, null);

        assertThat(params.page()).isEqualTo(1);
        assertThat(params.size()).isEqualTo(20);
        assertThat(params.offset()).isZero();
    }

    @Test
    void clampsUnsupportedPageSizeToTwenty() {
        PageRequestParams params = PageRequestParams.of(3, 999);

        assertThat(params.page()).isEqualTo(3);
        assertThat(params.size()).isEqualTo(20);
        assertThat(params.offset()).isEqualTo(40);
    }

    @Test
    void acceptsSupportedPageSizes() {
        assertThat(PageRequestParams.of(2, 50).offset()).isEqualTo(50);
        assertThat(PageRequestParams.of(2, 100).offset()).isEqualTo(100);
        assertThat(PageRequestParams.of(2, 200).offset()).isEqualTo(200);
    }
}
