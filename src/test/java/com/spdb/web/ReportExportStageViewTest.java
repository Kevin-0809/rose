package com.spdb.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportStageViewTest {
    @Test
    void marksTheFailedStageAndFollowingStagesAsNotExecuted() {
        assertThat(ReportExportStageView.forCommand("FAILED", "FIELD_DETAILS"))
                .extracting(ReportExportStageView::state)
                .containsExactly("completed", "failed", "not-executed");
    }

    @Test
    void marksTheActiveStageWhileRunning() {
        assertThat(ReportExportStageView.forCommand("RUNNING", "FIELD_DETAILS"))
                .extracting(ReportExportStageView::state)
                .containsExactly("completed", "running", "pending");
    }
}
