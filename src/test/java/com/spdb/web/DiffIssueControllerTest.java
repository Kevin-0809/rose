package com.spdb.web;

import com.spdb.report.DiffIssueLedgerService;
import com.spdb.report.DiffIssueRow;
import com.spdb.report.DiffIssueSearch;
import com.spdb.report.DiffIssueUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffIssueControllerTest {
    @Test
    void listAppliesAllFiltersAndReturnsPagedResult() {
        DiffIssueLedgerService service = mock(DiffIssueLedgerService.class);
        PagedResult<DiffIssueRow> result = PagedResult.of(List.of(issue()), 1, PageRequestParams.of(2, 50));
        when(service.searchPaged(any(DiffIssueSearch.class), any(PageRequestParams.class))).thenReturn(result);
        DiffIssueController controller = new DiffIssueController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.list("FIELD", "OPEN", "svc-a", "支付", "张三",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"),
                LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-27"), "amount", 2, 50, model);

        assertThat(view).isEqualTo("diff-issues/list");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("issueLevel")).isEqualTo("FIELD");
        assertThat(model.getAttribute("moduleName")).isEqualTo("支付");
        verify(service).searchPaged(eq(new DiffIssueSearch("FIELD", "OPEN", "svc-a", "支付", "张三",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"),
                LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-27"), "amount")), eq(PageRequestParams.of(2, 50)));
    }

    @Test
    void updatePassesAllMaintenanceFields() {
        DiffIssueLedgerService service = mock(DiffIssueLedgerService.class);
        DiffIssueController controller = new DiffIssueController(service);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.update(7L, "数据问题", "分析", "方案", "RESOLVED", "需交易组",
                "李四", LocalDate.parse("2026-07-27"), LocalDate.parse("2026-07-28"),
                "2026-07-27T10:00:00", flash);

        assertThat(view).isEqualTo("redirect:/diff-issues/7");
        verify(service).update(7L, new DiffIssueUpdate("数据问题", "分析", "方案", "RESOLVED", "需交易组", "李四",
                LocalDate.parse("2026-07-27"), LocalDate.parse("2026-07-28")), LocalDateTime.parse("2026-07-27T10:00:00"));
    }

    @Test
    void updateShowsFriendlyMessageWhenOptimisticLockFails() {
        DiffIssueLedgerService service = mock(DiffIssueLedgerService.class);
        org.mockito.Mockito.doThrow(new OptimisticLockingFailureException("stale"))
                .when(service).update(eq(7L), any(DiffIssueUpdate.class), any(LocalDateTime.class));
        DiffIssueController controller = new DiffIssueController(service);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        controller.update(7L, null, null, null, "OPEN", null, null, null, null,
                "2026-07-27T10:00:00", flash);

        assertThat(flash.getFlashAttributes().get("error")).isEqualTo("该问题已被其他人更新，请刷新后再保存。");
    }

    private static DiffIssueRow issue() {
        return new DiffIssueRow(1L, "FIELD|svc-a|amount", "FIELD", "svc-a", "T1", "交易A", "支付", "张三",
                null, null, "amount", "数据问题", "描述", "分析", "方案", "OPEN", "需交易组", "李四",
                null, null, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-27"),
                "B1", "B2", 2L, LocalDateTime.parse("2026-07-27T10:00:00"));
    }
}
