package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationMockData;
import com.spdb.migration.MigrationProgressRow;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MigrationController {

    @GetMapping("/migration/commands")
    public String commandsPage(Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("commands", MigrationMockData.commandRows());
        model.addAttribute("form", MigrationCommandForm.empty());
        return "migration/commands";
    }

    @PostMapping("/migration/commands")
    public String createCommand(@ModelAttribute MigrationCommandForm form) {
        return "redirect:/migration/commands/2";
    }

    @GetMapping("/migration/commands/{id}")
    public String progressPage(@PathVariable long id, Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("progress", MigrationMockData.progress(id));
        return "migration/progress";
    }

    @GetMapping("/migration/commands/{id}/progress")
    @ResponseBody
    public MigrationProgressRow progressJson(@PathVariable long id) {
        return MigrationMockData.progress(id);
    }

    @PostMapping("/migration/commands/{id}/cancel")
    public String cancel(@PathVariable long id) {
        return "redirect:/migration/commands/" + id;
    }

    @PostMapping("/migration/commands/{id}/resume")
    public String resume(@PathVariable long id) {
        return "redirect:/migration/commands/" + id;
    }
}
