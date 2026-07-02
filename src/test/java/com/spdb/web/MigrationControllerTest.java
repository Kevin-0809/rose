package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationCommandRow;
import com.spdb.migration.MigrationCommandService;
import com.spdb.migration.MigrationProgressRow;
import com.spdb.migration.MigrationSqlCommandForm;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationControllerTest {

    @Test
    void commandsPageSearchesServiceWithPageParamsAndAddsModelAttributes() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        PagedResult<MigrationCommandRow> result = PagedResult.of(List.of(commandRow(7L)), 1, PageRequestParams.of(2, 50));
        when(service.search(PageRequestParams.of(2, 50))).thenReturn(result);
        when(service.sourceDataSource()).thenReturn("source_runtime");
        when(service.targetDataSource()).thenReturn("target_runtime");
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.commandsPage(2, 50, model);

        assertThat(view).isEqualTo("migration/commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("form")).isEqualTo(MigrationCommandForm.empty());
        assertThat(model.getAttribute("sourceDataSource")).isEqualTo("source_runtime");
        assertThat(model.getAttribute("targetDataSource")).isEqualTo("target_runtime");
        verify(service).search(PageRequestParams.of(2, 50));
    }

    @Test
    void commandsPageUsesRuntimeLabelsEvenWhenRowsAreEmpty() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        PagedResult<MigrationCommandRow> result = PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20));
        when(service.search(PageRequestParams.of(null, null))).thenReturn(result);
        when(service.sourceDataSource()).thenReturn("empty_source");
        when(service.targetDataSource()).thenReturn("empty_target");
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        controller.commandsPage(null, null, model);

        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("sourceDataSource")).isEqualTo("empty_source");
        assertThat(model.getAttribute("targetDataSource")).isEqualTo("empty_target");
    }

    @Test
    void createCommandRedirectsToCreatedId() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationCommandForm form = MigrationCommandForm.empty();
        when(service.createCommand(form)).thenReturn(42L);
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createCommand(form, model);

        assertThat(view).isEqualTo("redirect:/migration/commands/42");
        verify(service).createCommand(form);
    }

    @Test
    void createCommandReturnsFormWithErrorWhenValidationFails() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationCommandForm form = MigrationCommandForm.empty();
        PagedResult<MigrationCommandRow> result = PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20));
        when(service.createCommand(form)).thenThrow(new IllegalArgumentException("响应时间终点必须大于响应时间起点"));
        when(service.search(PageRequestParams.of(null, null))).thenReturn(result);
        when(service.sourceDataSource()).thenReturn("bxds");
        when(service.targetDataSource()).thenReturn("primary");
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createCommand(form, model);

        assertThat(view).isEqualTo("migration/commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("form")).isSameAs(form);
        assertThat(model.getAttribute("sourceDataSource")).isEqualTo("bxds");
        assertThat(model.getAttribute("targetDataSource")).isEqualTo("primary");
        assertThat(model.getAttribute("error")).isEqualTo("响应时间终点必须大于响应时间起点");
        verify(service).createCommand(form);
    }

    @Test
    void sqlCommandsPageUsesSeparateTemplateAndModel() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        PagedResult<MigrationCommandRow> result = PagedResult.of(List.of(commandRow(8L)), 1, PageRequestParams.of(1, 20));
        when(service.searchSql(PageRequestParams.of(1, 20))).thenReturn(result);
        when(service.sourceDataSource()).thenReturn("bxds");
        when(service.targetDataSource()).thenReturn("primary");
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.sqlCommandsPage(1, 20, model);

        assertThat(view).isEqualTo("migration/sql-commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration-sql");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("form")).isEqualTo(MigrationSqlCommandForm.empty());
        assertThat(model.getAttribute("sourceDataSource")).isEqualTo("bxds");
        assertThat(model.getAttribute("targetDataSource")).isEqualTo("primary");
        verify(service).searchSql(PageRequestParams.of(1, 20));
    }

    @Test
    void createSqlCommandRedirectsToCreatedProgressPage() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationSqlCommandForm form = new MigrationSqlCommandForm("select 1", "sql");
        when(service.createSqlCommand(form)).thenReturn(43L);
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createSqlCommand(form, model);

        assertThat(view).isEqualTo("redirect:/migration/commands/43");
        verify(service).createSqlCommand(form);
    }

    @Test
    void createSqlCommandReturnsSqlPageWithErrorWhenValidationFails() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationSqlCommandForm form = new MigrationSqlCommandForm("", "");
        PagedResult<MigrationCommandRow> result = PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20));
        when(service.createSqlCommand(form)).thenThrow(new IllegalArgumentException("Response SQL不能为空"));
        when(service.searchSql(PageRequestParams.of(null, null))).thenReturn(result);
        when(service.sourceDataSource()).thenReturn("bxds");
        when(service.targetDataSource()).thenReturn("primary");
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createSqlCommand(form, model);

        assertThat(view).isEqualTo("migration/sql-commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration-sql");
        assertThat(model.getAttribute("result")).isSameAs(result);
        assertThat(model.getAttribute("form")).isSameAs(form);
        assertThat(model.getAttribute("error")).isEqualTo("Response SQL不能为空");
        verify(service).createSqlCommand(form);
    }

    @Test
    void progressPageAddsServiceProgressToModel() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationProgressRow progress = progressRow(42L, "RUNNING");
        when(service.progress(42L)).thenReturn(progress);
        MigrationController controller = new MigrationController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.progressPage(42L, model);

        assertThat(view).isEqualTo("migration/progress");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("progress")).isSameAs(progress);
        verify(service).progress(42L);
    }

    @Test
    void progressJsonReturnsServiceProgress() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationProgressRow progress = progressRow(42L, "RUNNING");
        when(service.progress(42L)).thenReturn(progress);
        MigrationController controller = new MigrationController(service);

        MigrationProgressRow result = controller.progressJson(42L);

        assertThat(result).isSameAs(progress);
        assertThat(result.status()).isEqualTo("RUNNING");
        verify(service).progress(42L);
    }

    @Test
    void cancelRequestsServiceCancelAndRedirectsToProgressPage() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationController controller = new MigrationController(service);

        String view = controller.cancel(42L);

        assertThat(view).isEqualTo("redirect:/migration/commands/42");
        verify(service).requestCancel(42L);
    }

    @Test
    void resumeCallsServiceAndRedirectsToProgressPage() {
        MigrationCommandService service = mock(MigrationCommandService.class);
        MigrationController controller = new MigrationController(service);

        String view = controller.resume(5L);

        assertThat(view).isEqualTo("redirect:/migration/commands/5");
        verify(service).resume(5L);
    }

    private static MigrationCommandRow commandRow(long id) {
        return new MigrationCommandRow(
                id, "source", "target", "TIME_RANGE", "RUNNING", 1L, 2L, 3600L, 2,
                10L, 4L, 0L, 100L, 2L, 1L, "10s",
                null, null, null, null, null, null, "remark"
        );
    }

    private static MigrationProgressRow progressRow(long id, String status) {
        return new MigrationProgressRow(
                id, "source", "target", status, 1L, 2L, 3600L, 2,
                10L, 4L, 0L, 100L, 2L, 1L, 10L,
                null, null, null, List.of()
        );
    }
}
