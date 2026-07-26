package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.FieldDiffTrackingExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import com.spdb.sample.TransactionDiffTrackingExportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SampleControllerExportTest {

    @Test
    void transactionDiffExportStreamsDedicatedTransactionRows() throws Exception {
        SampleQueryService queryService = new SampleQueryService(null);
        RecordingExportService excelExportService = new RecordingExportService();
        SampleController controller = new SampleController(queryService, excelExportService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportTransactionDiffs(null, null, null, null, null, null, null, null, response);

        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Content-Disposition")).contains("transdiff_");
        assertThat(response.getHeader("Content-Disposition")).contains(".zip");
        assertThat(excelExportService.transactionDiffExportCalled).isTrue();
    }

    @Test
    void fieldDiffExportStreamsExcelAndTxtAsZipWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = new SampleQueryService(null);
        RecordingExportService excelExportService = new RecordingExportService();
        SampleController controller = new SampleController(queryService, excelExportService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportFieldDiffs(null, null, null, null, null, null, null, null, null, null, response);

        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(decodedFilename(response)).startsWith("fielddiff_").endsWith(".zip");
        assertThat(excelExportService.fieldDiffZipExportCalled).isTrue();
        assertThat(excelExportService.fieldDiffExportCalled).isFalse();
        assertThat(excelExportService.transactionDiffExportCalled).isFalse();
    }

    @Test
    void trackingExportRejectsNullBatchWithoutCallingService() {
        RecordingTrackingExportService trackingExportService = new RecordingTrackingExportService();
        SampleController controller = new SampleController(new SampleQueryService(null), new RecordingExportService(),
                trackingExportService);

        assertThatIllegalArgumentException().isThrownBy(() ->
                        controller.exportTransactionDiffTracking(null, new MockHttpServletResponse()))
                .withMessage("\u8bf7\u9009\u62e9\u6279\u6b21\u540e\u5bfc\u51fa");

        assertThat(trackingExportService.exportCalled).isFalse();
    }

    @Test
    void trackingExportRejectsBlankBatchWithoutCallingService() {
        RecordingTrackingExportService trackingExportService = new RecordingTrackingExportService();
        SampleController controller = new SampleController(new SampleQueryService(null), new RecordingExportService(),
                trackingExportService);

        assertThatIllegalArgumentException().isThrownBy(() ->
                        controller.exportTransactionDiffTracking("  ", new MockHttpServletResponse()))
                .withMessage("\u8bf7\u9009\u62e9\u6279\u6b21\u540e\u5bfc\u51fa");

        assertThat(trackingExportService.exportCalled).isFalse();
    }

    @Test
    void trackingExportStreamsTrimmedBatchAsTextDownload() throws Exception {
        RecordingTrackingExportService trackingExportService = new RecordingTrackingExportService();
        SampleController controller = new SampleController(new SampleQueryService(null), new RecordingExportService(),
                trackingExportService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportTransactionDiffTracking("  batch-001  ", response);

        assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(decodedFilename(response)).matches("trandiff_hf_\\d{14}\\.txt");
        assertThat(trackingExportService.sourceBatchId).isEqualTo("batch-001");
        assertThat(response.getContentAsString()).isEqualTo("tracking export");
    }

    @Test
    void fieldTrackingExportRejectsMissingOrBlankBatchWithoutCallingService() {
        RecordingFieldTrackingExportService trackingExportService = new RecordingFieldTrackingExportService();
        SampleController controller = new SampleController(new SampleQueryService(null), new RecordingExportService(),
                null, trackingExportService);

        assertThatIllegalArgumentException().isThrownBy(() ->
                        controller.exportFieldDiffTracking(null, new MockHttpServletResponse()))
                .withMessage("\u8bf7\u9009\u62e9\u6279\u6b21\u540e\u5bfc\u51fa");
        assertThatIllegalArgumentException().isThrownBy(() ->
                        controller.exportFieldDiffTracking("  ", new MockHttpServletResponse()))
                .withMessage("\u8bf7\u9009\u62e9\u6279\u6b21\u540e\u5bfc\u51fa");

        assertThat(trackingExportService.exportCalled).isFalse();
    }

    @Test
    void fieldTrackingExportStreamsTrimmedBatchAsTextDownload() throws Exception {
        RecordingFieldTrackingExportService trackingExportService = new RecordingFieldTrackingExportService();
        SampleController controller = new SampleController(new SampleQueryService(null), new RecordingExportService(),
                null, trackingExportService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportFieldDiffTracking("  batch-002  ", response);

        assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(decodedFilename(response)).matches("fielddiff_hf_\\d{14}\\.txt");
        assertThat(trackingExportService.sourceBatchId).isEqualTo("batch-002");
        assertThat(response.getContentAsString()).isEqualTo("field tracking export");
    }

    private String decodedFilename(MockHttpServletResponse response) {
        String header = response.getHeader("Content-Disposition");
        String prefix = "attachment; filename*=UTF-8''";
        assertThat(header).startsWith(prefix);
        return URLDecoder.decode(header.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private static class RecordingExportService extends SampleExcelExportService {
        boolean transactionDiffExportCalled;
        boolean fieldDiffExportCalled;
        boolean fieldDiffZipExportCalled;

        @Override
        public void streamTransactionDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            transactionDiffExportCalled = true;
        }

        @Override
        public void streamFieldDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            fieldDiffExportCalled = true;
        }

        @Override
        public void streamFieldDiffZipExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            fieldDiffZipExportCalled = true;
        }
    }

    private static class RecordingTrackingExportService extends TransactionDiffTrackingExportService {
        boolean exportCalled;
        String sourceBatchId;

        RecordingTrackingExportService() {
            super(null, new DataSourceTransactionManager());
        }

        @Override
        public void export(String sourceBatchId, OutputStream outputStream) {
            exportCalled = true;
            this.sourceBatchId = sourceBatchId;
            try {
                outputStream.write("tracking export".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static class RecordingFieldTrackingExportService extends FieldDiffTrackingExportService {
        boolean exportCalled;
        String sourceBatchId;

        RecordingFieldTrackingExportService() {
            super(null, new DataSourceTransactionManager());
        }

        @Override
        public void export(String sourceBatchId, OutputStream outputStream) {
            exportCalled = true;
            this.sourceBatchId = sourceBatchId;
            try {
                outputStream.write("field tracking export".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
