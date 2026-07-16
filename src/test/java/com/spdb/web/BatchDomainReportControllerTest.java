package com.spdb.web;

import com.spdb.report.BatchDomainFieldStatRow;
import com.spdb.report.BatchDomainReportCommandRow;
import com.spdb.report.BatchDomainReportRow;
import com.spdb.report.BatchDomainReportService;
import com.spdb.report.BatchReportGapRow;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BatchDomainReportControllerTest {

    @Test
    void pageAddsSelectedBatchCommandAndSucceededReportRows() {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        BatchDomainReportCommandRow command = command("SUCCEEDED");
        List<BatchDomainReportRow> transactionStats = List.of(new BatchDomainReportRow("batch-1", "payment", 2, 9, 1, 2, 3, 4, 5));
        List<BatchDomainFieldStatRow> fieldStats = List.of(new BatchDomainFieldStatRow("batch-1", "payment", 8, 3, 5));
        List<BatchReportGapRow> gaps = List.of(new BatchReportGapRow("batch-1", "UNMAPPED_FIELD", "svc", "request", "amount", 4));
        service.command = command;
        service.transactionStats = transactionStats;
        service.fieldStats = fieldStats;
        service.gaps = gaps;
        BatchDomainReportController controller = new BatchDomainReportController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.domainReports("batch-1", model);

        assertThat(view).isEqualTo("sampling/domain-reports");
        assertThat(model.getAttribute("active")).isEqualTo("batch-domain-reports");
        assertThat(model.getAttribute("batchId")).isEqualTo("batch-1");
        assertThat(model.getAttribute("command")).isSameAs(command);
        assertThat(model.getAttribute("transactionStats")).isSameAs(transactionStats);
        assertThat(model.getAttribute("fieldStats")).isSameAs(fieldStats);
        assertThat(model.getAttribute("gaps")).isSameAs(gaps);
    }

    @Test
    void pageDoesNotQueryReportRowsUntilTheCommandSucceeds() {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        service.command = command("RUNNING");
        BatchDomainReportController controller = new BatchDomainReportController(service);
        ConcurrentModel model = new ConcurrentModel();

        controller.domainReports("batch-1", model);

        assertThat(model.getAttribute("transactionStats")).isEqualTo(List.of());
        assertThat(model.getAttribute("fieldStats")).isEqualTo(List.of());
        assertThat(model.getAttribute("gaps")).isEqualTo(List.of());
    }

    @Test
    void createStartsReportAndRedirectsToSelectedBatch() {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        BatchDomainReportController controller = new BatchDomainReportController(service);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.create("batch-1", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/sampling/domain-reports");
        assertThat(redirectAttributes.getAttribute("batchId")).isEqualTo("batch-1");
        assertThat(service.startedBatchId).isEqualTo("batch-1");
    }

    @Test
    void createKeepsBatchSelectionAndShowsServiceError() {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        service.createError = new IllegalStateException("批次不可用");
        BatchDomainReportController controller = new BatchDomainReportController(service);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.create("batch-1", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/sampling/domain-reports");
        assertThat(redirectAttributes.getAttribute("batchId")).isEqualTo("batch-1");
        assertThat(redirectAttributes.getFlashAttributes().get("error")).isEqualTo("批次不可用");
    }

    @Test
    void progressReturnsCurrentCommand() {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        BatchDomainReportCommandRow command = command("RUNNING");
        service.command = command;
        BatchDomainReportController controller = new BatchDomainReportController(service);

        assertThat(controller.progress("batch-1")).isSameAs(command);
    }

    @Test
    void createEncodesReservedBatchIdThroughRedirectAttributes() throws Exception {
        FakeBatchDomainReportService service = new FakeBatchDomainReportService();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BatchDomainReportController(service)).build();

        mvc.perform(post("/sampling/domain-reports").param("batchId", "batch&name#1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sampling/domain-reports?batchId=batch%26name%231"));

        assertThat(service.startedBatchId).isEqualTo("batch&name#1");
    }

    private static BatchDomainReportCommandRow command(String status) {
        return new BatchDomainReportCommandRow(1L, "batch-1", status, null, null, null, null);
    }

    private static final class FakeBatchDomainReportService extends BatchDomainReportService {
        private BatchDomainReportCommandRow command;
        private List<BatchDomainReportRow> transactionStats = List.of();
        private List<BatchDomainFieldStatRow> fieldStats = List.of();
        private List<BatchReportGapRow> gaps = List.of();
        private RuntimeException createError;
        private String startedBatchId;

        private FakeBatchDomainReportService() {
            super(null, new org.springframework.transaction.PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            });
        }

        @Override
        public BatchDomainReportCommandRow findCommand(String batchId) {
            return command;
        }

        @Override
        public List<BatchDomainReportRow> findTransactionStats(String batchId) {
            return transactionStats;
        }

        @Override
        public List<BatchDomainFieldStatRow> findFieldStats(String batchId) {
            return fieldStats;
        }

        @Override
        public List<BatchReportGapRow> findGaps(String batchId) {
            return gaps;
        }

        @Override
        public void createAndStartCommand(String batchId) {
            if (createError != null) {
                throw createError;
            }
            startedBatchId = batchId;
        }
    }
}
