package com.spdb.web;

import com.spdb.config.ConfigImportService;
import com.spdb.config.TransactionListImportProgressRow;
import com.spdb.config.TransactionListImportTaskLauncher;
import com.spdb.config.TransactionListImportTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigImportControllerTest {

    @Test
    void importListCreatesAsyncTaskAndRedirectsToProgressPage() throws Exception {
        ConfigImportService importService = null;
        FakeTaskService taskService = new FakeTaskService(42L, progressRow());
        ConfigImportController controller = new ConfigImportController(importService, taskService);
        ConcurrentModel model = new ConcurrentModel();
        MockMultipartFile file = new MockMultipartFile(
                "listFile",
                "list.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3}
        );

        String view = controller.importList(file, model);

        assertThat(view).isEqualTo("redirect:/config/import/list-tasks/42");
        assertThat(taskService.createdPath()).isNotNull();
        assertThat(taskService.createdOriginalFilename()).isEqualTo("list.xlsx");
    }

    @Test
    void listImportProgressPageAddsProgressToModel() {
        ConfigImportService importService = null;
        TransactionListImportProgressRow progress = progressRow();
        FakeTaskService taskService = new FakeTaskService(42L, progress);
        ConfigImportController controller = new ConfigImportController(importService, taskService);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.listImportProgressPage(42L, model);

        assertThat(view).isEqualTo("config/import-list-progress");
        assertThat(model.getAttribute("active")).isEqualTo("configImport");
        assertThat(model.getAttribute("progress")).isSameAs(progress);
        assertThat(taskService.progressTaskId()).isEqualTo(42L);
    }

    @Test
    void listImportProgressJsonReturnsServiceProgress() {
        ConfigImportService importService = null;
        TransactionListImportProgressRow progress = progressRow();
        FakeTaskService taskService = new FakeTaskService(42L, progress);
        ConfigImportController controller = new ConfigImportController(importService, taskService);

        TransactionListImportProgressRow result = controller.listImportProgressJson(42L);

        assertThat(result).isSameAs(progress);
        assertThat(taskService.progressTaskId()).isEqualTo(42L);
    }

    @Test
    void resumeListImportRelaunchesTaskAndRedirectsToProgressPage() {
        ConfigImportService importService = null;
        FakeTaskService taskService = new FakeTaskService(42L, progressRow());
        ConfigImportController controller = new ConfigImportController(importService, taskService);

        String view = controller.resumeListImport(42L);

        assertThat(view).isEqualTo("redirect:/config/import/list-tasks/42");
        assertThat(taskService.resumedTaskId()).isEqualTo(42L);
    }


    private TransactionListImportProgressRow progressRow() {
        return new TransactionListImportProgressRow(
                42L,
                "RUNNING",
                "list.xlsx",
                100,
                10,
                3,
                1,
                30,
                1,
                2,
                3,
                4,
                5,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null
        );
    }

    private static class FakeTaskService extends TransactionListImportTaskService {
        private final long taskId;
        private final TransactionListImportProgressRow progress;
        private Path createdPath;
        private String createdOriginalFilename;
        private long progressTaskId;
        private long resumedTaskId;

        FakeTaskService(long taskId, TransactionListImportProgressRow progress) {
            super(new NamedParameterJdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:fake_task_service", "sa", "")), emptyProvider());
            this.taskId = taskId;
            this.progress = progress;
        }

        @Override
        public long createTask(Path listFilePath, String originalFilename) {
            this.createdPath = listFilePath;
            this.createdOriginalFilename = originalFilename;
            return taskId;
        }

        @Override
        public TransactionListImportProgressRow progress(long taskId) {
            this.progressTaskId = taskId;
            return progress;
        }

        @Override
        public void resume(long taskId) {
            this.resumedTaskId = taskId;
        }

        Path createdPath() {
            return createdPath;
        }

        String createdOriginalFilename() {
            return createdOriginalFilename;
        }

        long progressTaskId() {
            return progressTaskId;
        }

        long resumedTaskId() {
            return resumedTaskId;
        }
    }

    private static ObjectProvider<TransactionListImportTaskLauncher> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public TransactionListImportTaskLauncher getObject(Object... args) {
                return null;
            }

            @Override
            public TransactionListImportTaskLauncher getIfAvailable() {
                return null;
            }

            @Override
            public TransactionListImportTaskLauncher getIfUnique() {
                return null;
            }

            @Override
            public TransactionListImportTaskLauncher getObject() {
                return null;
            }
        };
    }
}
