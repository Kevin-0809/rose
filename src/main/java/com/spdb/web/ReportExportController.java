package com.spdb.web;

import com.spdb.report.ReportExportCommandRow;
import com.spdb.report.ReportExportCommandService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class ReportExportController {
    private final ReportExportCommandService reportExportCommandService;

    public ReportExportController(ReportExportCommandService reportExportCommandService) {
        this.reportExportCommandService = reportExportCommandService;
    }

    @GetMapping("/report-exports")
    public String commands(Model model) {
        model.addAttribute("active", "report-exports");
        return "report-exports/commands";
    }

    @PostMapping("/report-exports")
    public String create(RedirectAttributes redirectAttributes) {
        String batchId = reportExportCommandService.createAndStart();
        redirectAttributes.addFlashAttribute("message", "报表明细导出任务已提交：" + batchId);
        return "redirect:/report-exports/" + batchId;
    }

    @GetMapping("/report-exports/{batchId}")
    public String detail(@PathVariable String batchId, Model model) {
        ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
        model.addAttribute("active", "report-exports");
        model.addAttribute("command", command);
        if (command != null && "SUCCEEDED".equals(command.status())) {
            model.addAttribute("summaries", reportExportCommandService.findSummaries(batchId));
            model.addAttribute("transactionDetails", reportExportCommandService.findTransactionDetails(batchId));
            model.addAttribute("fieldDetails", reportExportCommandService.findFieldDetails(batchId));
        } else {
            model.addAttribute("summaries", List.of());
            model.addAttribute("transactionDetails", List.of());
            model.addAttribute("fieldDetails", List.of());
        }
        return "report-exports/detail";
    }

    @GetMapping("/report-exports/{batchId}/progress")
    @ResponseBody
    public ReportExportCommandRow progress(@PathVariable String batchId) {
        return reportExportCommandService.findByBatchId(batchId);
    }
}
