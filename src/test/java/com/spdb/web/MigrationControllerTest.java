package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationMockData;
import com.spdb.migration.MigrationProgressRow;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationControllerTest {

    @Test
    void commandsPageAddsRowsAndFormToModel() {
        MigrationController controller = new MigrationController();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.commandsPage(model);

        assertThat(view).isEqualTo("migration/commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("commands")).isEqualTo(MigrationMockData.commandRows());
        assertThat(model.getAttribute("form")).isEqualTo(MigrationCommandForm.empty());
    }

    @Test
    void createCommandRedirectsToRunningExample() {
        MigrationController controller = new MigrationController();

        String view = controller.createCommand(MigrationCommandForm.empty());

        assertThat(view).isEqualTo("redirect:/migration/commands/2");
    }

    @Test
    void progressPageAddsProgressToModel() {
        MigrationController controller = new MigrationController();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.progressPage(2L, model);

        assertThat(view).isEqualTo("migration/progress");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("progress")).isEqualTo(MigrationMockData.progress(2L));
    }

    @Test
    void progressJsonReturnsRunningProgress() {
        MigrationController controller = new MigrationController();

        MigrationProgressRow result = controller.progressJson(2L);

        assertThat(result).isEqualTo(MigrationMockData.progress(2L));
        assertThat(result.status()).isEqualTo("RUNNING");
    }

    @Test
    void cancelRedirectsToProgressPage() {
        MigrationController controller = new MigrationController();

        String view = controller.cancel(2L);

        assertThat(view).isEqualTo("redirect:/migration/commands/2");
    }

    @Test
    void resumeRedirectsToProgressPage() {
        MigrationController controller = new MigrationController();

        String view = controller.resume(5L);

        assertThat(view).isEqualTo("redirect:/migration/commands/5");
    }
}
