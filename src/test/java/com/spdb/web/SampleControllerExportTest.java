package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
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
    void fieldDiffExportStreamsCombinedRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = new SampleQueryService(null);
        RecordingExportService excelExportService = new RecordingExportService();
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportFieldDiffs(null, null, null, null, null, null, null, null, null, null, new MockHttpServletResponse());

        assertThat(excelExportService.fieldDiffExportCalled).isTrue();
        assertThat(excelExportService.transactionDiffExportCalled).isFalse();
    }

    private static class RecordingExportService extends SampleExcelExportService {
        boolean transactionDiffExportCalled;
        boolean fieldDiffExportCalled;

        @Override
        public void streamTransactionDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            transactionDiffExportCalled = true;
        }

        @Override
        public void streamFieldDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            fieldDiffExportCalled = true;
        }
    }
}
