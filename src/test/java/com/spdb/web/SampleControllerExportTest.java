package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SampleControllerExportTest {

    @Test
    void groupExportStreamsRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportGroups(null, null, null, null, null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamGroups(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportGroups(any());
    }

    @Test
    void detailExportStreamsRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportDetails(null, null, null, null, null, null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamDetails(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportDetails(any());
    }

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
        assertThat(excelExportService.detailsExportCalled).isFalse();
    }

    @Test
    void detailFieldExportStreamsRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportDetailFields(null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamDetailFields(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportDetailFields(any());
    }

    @Test
    void fieldDiffExportStreamsCombinedRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportFieldDiffs(null, null, null, null, null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamFieldDiffExport(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportDetails(any());
        verify(queryService, never()).exportDetailFields(any());
    }

    private static class RecordingExportService extends SampleExcelExportService {
        boolean transactionDiffExportCalled;
        boolean detailsExportCalled;

        @Override
        public void streamTransactionDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            transactionDiffExportCalled = true;
        }

        @Override
        public void streamDetails(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
            detailsExportCalled = true;
        }
    }
}
