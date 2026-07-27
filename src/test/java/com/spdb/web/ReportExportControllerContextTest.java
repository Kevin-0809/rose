package com.spdb.web;

import com.spdb.report.ReportExportCommandService;
import com.spdb.report.ReportExportExcelService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportExportControllerContextTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withBean(ReportExportCommandService.class, () -> mock(ReportExportCommandService.class))
            .withBean(ReportExportExcelService.class, () -> mock(ReportExportExcelService.class))
            .withBean(ReportExportController.class);

    @Test
    void springCanCreateReportExportControllerWithItsAutowiredConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ReportExportController.class));
    }
}
