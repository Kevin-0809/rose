package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AppCssStyleTest {

    @Test
    void usesMinimalTechAdminTokens() throws IOException {
        String css = new String(
                getClass().getResourceAsStream("/static/css/app.css").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(css).contains("--bg-page: #09111f");
        assertThat(css).contains("--primary: #38bdf8");
        assertThat(css).contains("--text-heading: #e5edf7");
        assertThat(css).contains("--panel-shadow: none");
        assertThat(css).contains(".layout-frame");
        assertThat(css).contains("grid-template-columns: minmax(0, 37%) minmax(168px, 20%) minmax(0, 43%)");
        assertThat(css).contains(".record-track {\n  grid-column: 2 / 4;");
        assertThat(css).contains(".recording-branch");
        assertThat(css).contains("width: calc(31.5% + 265px)");
        assertThat(css).contains(".recording-branch-pulse");
        assertThat(css).contains("@keyframes recordingBranchFlow");
        assertThat(css).contains(".table-wrap-wide");
        assertThat(css).contains(".table-compact th,\n.table-compact td");
        assertThat(css).contains("white-space: nowrap");
        assertThat(css).contains("text-overflow: ellipsis");
        assertThat(css).contains(".field-diff-table");
        assertThat(css).contains("min-width: 2200px");
        assertThat(css).contains(".col-field-list");
        assertThat(css).contains(".command-table");
        assertThat(css).contains("-webkit-line-clamp: 2");
        assertThat(css).contains(".command-actions");
        assertThat(css).contains(".command-actions .btn");
        assertThat(css).contains(".summary-actions");
        assertThat(css).contains("grid-column: span 4");
        assertThat(css).contains("flex-wrap: nowrap");
        assertThat(css).doesNotContain(".record-track::before");
        assertThat(css).doesNotContain("radial-gradient");
        assertThat(css).doesNotContain("box-shadow: var(--shadow-card)");
    }
}
