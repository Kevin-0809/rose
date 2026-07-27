package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DiffIssueTemplateTest {
    @Test
    void listKeepsFiltersAndShowsPagedLedgerRows() throws Exception {
        String html = template("/templates/diff-issues/list.html");

        assertThat(html)
                .contains("差异问题台账")
                .contains("issue-filter-panel")
                .contains("issue-filter-grid")
                .contains("issue-filter-actions")
                .contains("name=\"moduleName\"")
                .contains("name=\"transactionOwner\"")
                .contains("name=\"firstSeenFrom\"")
                .contains("name=\"lastSeenTo\"")
                .contains("${result.rows()}")
                .contains("${result.page() - 1}")
                .contains("size=${result.size()}");
    }

    @Test
    void detailShowsMaintenanceFieldsAndOptimisticLockToken() throws Exception {
        String html = template("/templates/diff-issues/detail.html");

        assertThat(html)
                .contains("维护差异问题")
                .contains("issue-readonly-panel")
                .contains("issue-maintenance-panel")
                .contains("issue-form-actions")
                .contains("name=\"updatedAt\"")
                .contains("name=\"coordinationRequired\"")
                .contains("name=\"preliminaryAnalysis\"")
                .contains("name=\"finalSolution\"")
                .contains("${error}")
                .contains("${message}");
    }

    private String template(String path) throws Exception {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }
}
