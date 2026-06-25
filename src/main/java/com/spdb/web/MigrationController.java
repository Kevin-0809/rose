package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationCommandService;
import com.spdb.migration.MigrationProgressRow;
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
}
