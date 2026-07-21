package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationCommandService;
import com.spdb.migration.MigrationProgressRow;
import com.spdb.migration.MigrationSqlCommandForm;
import com.spdb.migration.MigrationTranCodeCommandForm;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MigrationController {

    private final MigrationCommandService migrationCommandService;

    public MigrationController(MigrationCommandService migrationCommandService) {
        this.migrationCommandService = migrationCommandService;
    }

    @GetMapping("/migration/commands")
    public String commandsPage(@RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        model.addAttribute("active", "migration");
        model.addAttribute("result", migrationCommandService.search(params));
        model.addAttribute("form", MigrationCommandForm.empty());
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
        return "migration/commands";
    }

    @PostMapping("/migration/commands")
    public String createCommand(@ModelAttribute MigrationCommandForm form, Model model) {
        try {
            long createdId = migrationCommandService.createCommand(form);
            return "redirect:/migration/commands/" + createdId;
        } catch (IllegalArgumentException ex) {
            addCommandsPageModel(model, form);
            model.addAttribute("error", ex.getMessage());
            return "migration/commands";
        }
    }

    @GetMapping("/migration/sql-commands")
    public String sqlCommandsPage(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        model.addAttribute("active", "migration-sql");
        model.addAttribute("result", migrationCommandService.searchSql(params));
        model.addAttribute("form", MigrationSqlCommandForm.empty());
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
        return "migration/sql-commands";
    }

    @PostMapping("/migration/sql-commands")
    public String createSqlCommand(@ModelAttribute MigrationSqlCommandForm form, Model model) {
        try {
            long createdId = migrationCommandService.createSqlCommand(form);
            return "redirect:/migration/commands/" + createdId;
        } catch (IllegalArgumentException ex) {
            addSqlCommandsPageModel(model, form);
            model.addAttribute("error", ex.getMessage());
            return "migration/sql-commands";
        }
    }

    @GetMapping("/migration/tran-code-commands")
    public String tranCodeCommandsPage(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        model.addAttribute("active", "migration-tran-code");
        model.addAttribute("result", migrationCommandService.searchTranCode(params));
        model.addAttribute("form", MigrationTranCodeCommandForm.empty());
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
        return "migration/tran-code-commands";
    }

    @PostMapping("/migration/tran-code-commands")
    public String createTranCodeCommand(@ModelAttribute MigrationTranCodeCommandForm form, Model model) {
        try {
            long createdId = migrationCommandService.createTranCodeCommand(form);
            return "redirect:/migration/commands/" + createdId;
        } catch (IllegalArgumentException ex) {
            addTranCodeCommandsPageModel(model, form);
            model.addAttribute("error", ex.getMessage());
            return "migration/tran-code-commands";
        }
    }

    @GetMapping("/migration/commands/{id}")
    public String progressPage(@PathVariable long id, Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("progress", migrationCommandService.progress(id));
        return "migration/progress";
    }

    @GetMapping("/migration/commands/{id}/progress")
    @ResponseBody
    public MigrationProgressRow progressJson(@PathVariable long id) {
        return migrationCommandService.progress(id);
    }

    @PostMapping("/migration/commands/{id}/cancel")
    public String cancel(@PathVariable long id) {
        migrationCommandService.requestCancel(id);
        return "redirect:/migration/commands/" + id;
    }

    @PostMapping("/migration/commands/{id}/resume")
    public String resume(@PathVariable long id) {
        migrationCommandService.resume(id);
        return "redirect:/migration/commands/" + id;
    }

    private void addCommandsPageModel(Model model, MigrationCommandForm form) {
        model.addAttribute("active", "migration");
        model.addAttribute("result", migrationCommandService.search(PageRequestParams.of(null, null)));
        model.addAttribute("form", form);
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
    }

    private void addSqlCommandsPageModel(Model model, MigrationSqlCommandForm form) {
        model.addAttribute("active", "migration-sql");
        model.addAttribute("result", migrationCommandService.searchSql(PageRequestParams.of(null, null)));
        model.addAttribute("form", form);
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
    }

    private void addTranCodeCommandsPageModel(Model model, MigrationTranCodeCommandForm form) {
        model.addAttribute("active", "migration-tran-code");
        model.addAttribute("result", migrationCommandService.searchTranCode(PageRequestParams.of(null, null)));
        model.addAttribute("form", form);
        model.addAttribute("sourceDataSource", migrationCommandService.sourceDataSource());
        model.addAttribute("targetDataSource", migrationCommandService.targetDataSource());
    }
}
