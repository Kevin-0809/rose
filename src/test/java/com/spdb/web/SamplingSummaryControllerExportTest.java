package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SamplingSummarySearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SamplingSummaryControllerExportTest {

    @Test
    void serviceReportExportStreamsWorkbookToResponse() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SamplingSummaryController controller = new SamplingSummaryController(queryService, excelExportService);

        controller.exportServiceReport("BATCH_RPT", "20260611", new MockHttpServletResponse());

        verify(excelExportService).streamServiceReport(
                any(SampleQueryService.class),
                any(SamplingSummarySearchCriteria.class),
                any(OutputStream.class)
        );
    }
}
