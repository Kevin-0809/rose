package com.spdb.web;

import com.spdb.report.ReportExportCommandRow;
import com.spdb.report.ReportExportCommandService;
import com.spdb.report.ReportExportFieldDetailRow;
import com.spdb.report.ReportExportSummaryRow;
import com.spdb.report.ReportExportTransactionDetailRow;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportControllerTest {

    @Test
    void commandsShowsBatchesWithBatchFilterAndFiftyRowsPerPage() {
        FakeReportExportCommandService service = new FakeReportExportCommandService();
        PagedResult<ReportExportCommandRow> result = PagedResult.of(List.of(command("RUNNING")), 51,
                PageRequestParams.of(2, 50));
        service.searchResult = result;
        ReportExportController controller = new ReportExportController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.commands("RPT202607", 2, model);

        assertThat(view).isEqualTo("report-exports/commands");
        assertThat(model.getAttribute("batchId")).isEqualTo("RPT202607");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(service.searchBatchId).isEqualTo("RPT202607");
        assertThat(service.searchPage.size()).isEqualTo(50);
    }

    @Test
    void detailShowsSucceededExportSummaryAndBothDetailTypes() {
        FakeReportExportCommandService service = new FakeReportExportCommandService();
        service.command = command("SUCCEEDED");
        service.summaries = List.of(new ReportExportSummaryRow(1L, "RPT1", "20260726", "支付", 2, 10, 1, 2, 3, 4, 5, 6, new BigDecimal("0.70000000")));
        service.transactionDetails = PagedResult.of(List.of(new ReportExportTransactionDetailRow(1L, 1L, "svc-a", "O1", "D1", "T001", "交易A", "支付", "原错误", "新错误")), 51, PageRequestParams.of(2, 50));
        service.fieldDetails = PagedResult.of(List.of(new ReportExportFieldDetailRow(1L, 1L, "svc-a", "T001", "交易A", "支付", "items.0", "amount", "UNMAPPED", "原值", "新值")), 101, PageRequestParams.of(3, 50));
        ReportExportController controller = new ReportExportController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.detail("RPT1", 2, 3, model);

        assertThat(view).isEqualTo("report-exports/detail");
        assertThat(model.getAttribute("active")).isEqualTo("report-exports");
        assertThat(model.getAttribute("command")).isSameAs(service.command);
        assertThat(model.getAttribute("summaries")).isSameAs(service.summaries);
        assertThat(model.getAttribute("transactionDetails")).isSameAs(service.transactionDetails);
        assertThat(model.getAttribute("fieldDetails")).isSameAs(service.fieldDetails);
        assertThat(service.transactionPage).isEqualTo(PageRequestParams.of(2, 50));
        assertThat(service.fieldPage).isEqualTo(PageRequestParams.of(3, 50));
    }

    @Test
    void detailHidesRowsUntilExportSucceeds() {
        FakeReportExportCommandService service = new FakeReportExportCommandService();
        service.command = command("RUNNING");
        ReportExportController controller = new ReportExportController(service);
        ConcurrentModel model = new ConcurrentModel();

        controller.detail("RPT1", null, null, model);

        assertThat(model.getAttribute("summaries")).isEqualTo(List.of());
        PagedResult<?> transactionDetails = (PagedResult<?>) model.getAttribute("transactionDetails");
        assertThat(transactionDetails.rows()).isEmpty();
        assertThat(transactionDetails.total()).isZero();
        assertThat(transactionDetails.size()).isEqualTo(50);
        PagedResult<?> fieldDetails = (PagedResult<?>) model.getAttribute("fieldDetails");
        assertThat(fieldDetails.rows()).isEmpty();
        assertThat(fieldDetails.total()).isZero();
        assertThat(fieldDetails.size()).isEqualTo(50);
    }

    @Test
    void createStartsExportAndReturnsToTheFilteredBatchList() {
        FakeReportExportCommandService service = new FakeReportExportCommandService();
        service.createdBatchId = "RPT20260726-0001";
        ReportExportController controller = new ReportExportController(service);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.create(redirectAttributes);

        assertThat(view).isEqualTo("redirect:/report-exports");
        assertThat(redirectAttributes.getAttribute("batchId")).isEqualTo("RPT20260726-0001");
        assertThat(redirectAttributes.getFlashAttributes().get("message")).isEqualTo("报表明细导出任务已提交：RPT20260726-0001");
    }

    private static ReportExportCommandRow command(String status) {
        return new ReportExportCommandRow(1L, "RPT1", "20260726", status, null, null, null, LocalDateTime.now());
    }

    private static final class FakeReportExportCommandService extends ReportExportCommandService {
        private String createdBatchId;
        private ReportExportCommandRow command;
        private List<ReportExportSummaryRow> summaries = List.of();
        private PagedResult<ReportExportTransactionDetailRow> transactionDetails = PagedResult.of(List.of(), 0, PageRequestParams.of(null, 50));
        private PagedResult<ReportExportFieldDetailRow> fieldDetails = PagedResult.of(List.of(), 0, PageRequestParams.of(null, 50));
        private PagedResult<ReportExportCommandRow> searchResult = PagedResult.of(List.of(), 0, PageRequestParams.of(null, 50));
        private String searchBatchId;
        private PageRequestParams searchPage;
        private PageRequestParams transactionPage;
        private PageRequestParams fieldPage;

        private FakeReportExportCommandService() {
            super(null, null);
        }

        @Override
        public String createAndStart() {
            return createdBatchId;
        }

        @Override
        public PagedResult<ReportExportCommandRow> searchCommands(String batchId, PageRequestParams page) {
            searchBatchId = batchId;
            searchPage = page;
            return searchResult;
        }

        @Override
        public ReportExportCommandRow findByBatchId(String batchId) {
            return command;
        }

        @Override
        public List<ReportExportSummaryRow> findSummaries(String batchId) {
            return summaries;
        }

        @Override
        public PagedResult<ReportExportTransactionDetailRow> searchTransactionDetails(String batchId, PageRequestParams page) {
            transactionPage = page;
            return transactionDetails;
        }

        @Override
        public PagedResult<ReportExportFieldDetailRow> searchFieldDetails(String batchId, PageRequestParams page) {
            fieldPage = page;
            return fieldDetails;
        }
    }
}
