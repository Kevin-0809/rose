package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

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
}
