package com.spdb.web;

import com.spdb.replay.ReplayTransactionCatalogForm;
import com.spdb.replay.ReplayTransactionCatalogRow;
import com.spdb.replay.ReplayTransactionCatalogSearch;
import com.spdb.replay.ReplayTransactionCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReplayTransactionCatalogControllerTest {
    @Test
    void listUsesCriteriaAndActiveNavigation() {
        ReplayTransactionCatalogService service = mock(ReplayTransactionCatalogService.class);
        var result = PagedResult.of(List.<ReplayTransactionCatalogRow>of(), 0, PageRequestParams.of(2, 50));
        when(service.search(any(), eq(PageRequestParams.of(2, 50)))).thenReturn(result);
        var controller = new ReplayTransactionCatalogController(service);
        var model = new ExtendedModelMap();
        assertThat(controller.list("T", "N", "D", "查询", "是", 2, 50, model)).isEqualTo("config/replay-catalog");
        assertThat(model.getAttribute("active")).isEqualTo("replay-catalog");
        verify(service).search(new ReplayTransactionCatalogSearch("T", "N", "D", "查询", "是"), PageRequestParams.of(2, 50));
    }

    @Test
    void routesNewEditSaveAndDelete() {
        ReplayTransactionCatalogService service = mock(ReplayTransactionCatalogService.class);
        var controller = new ReplayTransactionCatalogController(service);
        var model = new ExtendedModelMap();
        assertThat(controller.newForm(model)).isEqualTo("config/replay-catalog-edit");
        assertThat(controller.edit("T001", model)).isEqualTo("config/replay-catalog-edit");
        ReplayTransactionCatalogForm form = new ReplayTransactionCatalogForm("T001", null, null, null, null, null, null, null, null, null, null);
        assertThat(controller.save(form, null)).isEqualTo("redirect:/config/replay-catalog");
        assertThat(controller.save(form, "T001")).isEqualTo("redirect:/config/replay-catalog");
        assertThat(controller.delete("T001")).isEqualTo("redirect:/config/replay-catalog");
        verify(service).delete("T001");
    }

    @Test
    void importFeedbackIncludesCountAndFormattedTime() throws Exception {
        ReplayTransactionCatalogService service = mock(ReplayTransactionCatalogService.class);
        var importer = mock(com.spdb.replay.ReplayTransactionCatalogImportService.class);
        when(importer.importWorkbook(any(byte[].class))).thenReturn(3);
        var controller = new ReplayTransactionCatalogController(service, importer);
        var model = new ExtendedModelMap();
        var file = new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});

        controller.importWorkbook(file, model);

        assertThat(model.getAttribute("importCount")).isEqualTo(3);
        assertThat(model.getAttribute("importTime").toString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(model.getAttribute("importSuccess")).isEqualTo("导入成功");
    }
}
