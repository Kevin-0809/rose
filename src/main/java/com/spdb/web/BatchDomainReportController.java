package com.spdb.web;

import com.spdb.report.BatchDomainReportCommandRow;
import com.spdb.report.BatchDomainReportService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class BatchDomainReportController {
    private final BatchDomainReportService batchDomainReportService;

    public BatchDomainReportController(BatchDomainReportService batchDomainReportService) {
        this.batchDomainReportService = batchDomainReportService;
    }

    @GetMapping("/sampling/domain-reports")
    public String domainReports(@RequestParam(required = false) String batchId, Model model) {
        BatchDomainReportCommandRow command = hasText(batchId) ? batchDomainReportService.findCommand(batchId) : null;
        model.addAttribute("active", "batch-domain-reports");
        model.addAttribute("batchId", batchId);
        model.addAttribute("command", command);
        if (command != null && "SUCCEEDED".equals(command.status())) {
            model.addAttribute("transactionStats", batchDomainReportService.findTransactionStats(batchId));
            model.addAttribute("fieldStats", batchDomainReportService.findFieldStats(batchId));
            model.addAttribute("gaps", batchDomainReportService.findGaps(batchId));
        } else {
            model.addAttribute("transactionStats", List.of());
            model.addAttribute("fieldStats", List.of());
            model.addAttribute("gaps", List.of());
        }
        return "sampling/domain-reports";
    }

    @PostMapping("/sampling/domain-reports")
    public String create(@RequestParam String batchId, RedirectAttributes redirectAttributes) {
        try {
            batchDomainReportService.createAndStartCommand(batchId);
            redirectAttributes.addFlashAttribute("message", "批次领域报表任务已提交：" + batchId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        redirectAttributes.addAttribute("batchId", batchId);
        return "redirect:/sampling/domain-reports";
    }

    @GetMapping("/sampling/domain-reports/{batchId}/progress")
    @ResponseBody
    public BatchDomainReportCommandRow progress(@PathVariable String batchId) {
        return batchDomainReportService.findCommand(batchId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
