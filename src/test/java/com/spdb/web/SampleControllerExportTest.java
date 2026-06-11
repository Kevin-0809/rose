package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

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

        controller.exportGroups(null, null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamGroups(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportGroups(any());
    }

    @Test
    void detailExportStreamsRowsToResponseWithoutBuildingListFirst() throws Exception {
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleExcelExportService excelExportService = mock(SampleExcelExportService.class);
        SampleController controller = new SampleController(queryService, excelExportService);

        controller.exportDetails(null, null, null, null, null, null, null, null, new MockHttpServletResponse());

        verify(excelExportService).streamDetails(any(SampleQueryService.class), any(SampleSearchCriteria.class), any(OutputStream.class));
        verify(queryService, never()).exportDetails(any());
    }
}
