package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleFieldDiffRow;
import com.spdb.sample.SampleQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleControllerTest {

    @Test
    void fieldDiffDetailAddsRowAndReturnsDetailTemplate() {
        SampleFieldDiffRow row = fieldDiffRow();
        SampleController controller = new SampleController(new StubSampleQueryService(Optional.of(row)), new SampleExcelExportService());
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.fieldDiffDetail(501L, model);

        assertThat(view).isEqualTo("samples/field-diff-detail");
        assertThat(model.getAttribute("active")).isEqualTo("field-diffs");
        assertThat(model.getAttribute("row")).isSameAs(row);
    }

    @Test
    void fieldDiffDetailReturns404WhenRowIsMissing() {
        SampleController controller = new SampleController(new StubSampleQueryService(Optional.empty()), new SampleExcelExportService());

        assertThatThrownBy(() -> controller.fieldDiffDetail(999L, new ConcurrentModel()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private SampleFieldDiffRow fieldDiffRow() {
        return new SampleFieldDiffRow(
                501L,
                "20260611",
                "BATCH_A825",
                "A825",
                "S030030014FcyCollCrspBnkLkgQry",
                "bizjson",
                "HUOBDH",
                "CurrencyId",
                "CurrencyId",
                "币种",
                "MAPPED",
                "11111111111",
                "111",
                "222",
                "张伟",
                2L
        );
    }

    private static class StubSampleQueryService extends SampleQueryService {
        private final Optional<SampleFieldDiffRow> row;

        private StubSampleQueryService(Optional<SampleFieldDiffRow> row) {
            super(null);
            this.row = row;
        }

        @Override
        public Optional<SampleFieldDiffRow> fieldDiff(Long resultId) {
            return row;
        }
    }
}
