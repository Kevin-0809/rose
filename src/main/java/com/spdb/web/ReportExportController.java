package com.spdb.web;

import com.spdb.report.ReportExportCommandRow;
import com.spdb.report.ReportExportCommandService;
import com.spdb.report.ReportExportExcelService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class ReportExportController {
    private static final DateTimeFormatter EXPORT_FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final ReportExportCommandService reportExportCommandService;
    private final ReportExportExcelService reportExportExcelService;
    private final Clock clock;

    @Autowired
    public ReportExportController(ReportExportCommandService reportExportCommandService,
                                  ReportExportExcelService reportExportExcelService) {
        this(reportExportCommandService, reportExportExcelService, Clock.systemDefaultZone());
    }

    ReportExportController(ReportExportCommandService reportExportCommandService,
                           ReportExportExcelService reportExportExcelService,
                           Clock clock) {
        this.reportExportCommandService = reportExportCommandService;
        this.reportExportExcelService = reportExportExcelService;
        this.clock = clock;
    }

    @GetMapping("/report-exports")
    public String commands(@RequestParam(required = false) String batchId,
                           @RequestParam(required = false) Integer page,
                           Model model) {
        PageRequestParams pageParams = PageRequestParams.of(page, 50);
        PagedResult<ReportExportCommandRow> result = reportExportCommandService.searchCommands(batchId, pageParams);
        model.addAttribute("active", "report-exports");
        model.addAttribute("batchId", batchId);
        model.addAttribute("result", result);
        model.addAttribute("stageViewsByBatchId", result.rows().stream().collect(Collectors.toMap(
                ReportExportCommandRow::batchId,
                row -> ReportExportStageView.forCommand(row.status(), row.currentStage()),
                (first, ignored) -> first)));
        model.addAttribute("hasRunningCommands", result.rows().stream()
                .anyMatch(row -> "PENDING".equals(row.status()) || "RUNNING".equals(row.status())));
        return "report-exports/commands";
    }

    @PostMapping("/report-exports")
    public String create(RedirectAttributes redirectAttributes) {
        String batchId = reportExportCommandService.createAndStart();
        redirectAttributes.addFlashAttribute("message", "报表明细导出任务已提交：" + batchId);
        redirectAttributes.addAttribute("batchId", batchId);
        return "redirect:/report-exports";
    }

    @GetMapping("/report-exports/{batchId}")
    public String detail(@PathVariable String batchId,
                         @RequestParam(required = false) Integer transactionPage,
                         @RequestParam(required = false) Integer fieldPage,
                         Model model) {
        ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
        PageRequestParams transactionPageParams = PageRequestParams.of(transactionPage, 50);
        PageRequestParams fieldPageParams = PageRequestParams.of(fieldPage, 50);
        model.addAttribute("active", "report-exports");
        model.addAttribute("command", command);
        model.addAttribute("stageViews", command == null ? List.of()
                : ReportExportStageView.forCommand(command.status(), command.currentStage()));
        if (command != null && "SUCCEEDED".equals(command.status())) {
            model.addAttribute("summaries", reportExportCommandService.findSummaries(batchId));
            model.addAttribute("transactionDetails", reportExportCommandService.searchTransactionDetails(batchId, transactionPageParams));
            model.addAttribute("fieldDetails", reportExportCommandService.searchFieldDetails(batchId, fieldPageParams));
        } else {
            model.addAttribute("summaries", List.of());
            model.addAttribute("transactionDetails", PagedResult.of(List.of(), 0, transactionPageParams));
            model.addAttribute("fieldDetails", PagedResult.of(List.of(), 0, fieldPageParams));
        }
        return "report-exports/detail";
    }

    @GetMapping("/report-exports/{batchId}/progress")
    @ResponseBody
    public ReportExportProgressResponse progress(@PathVariable String batchId) {
        ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
        return command == null ? null : new ReportExportProgressResponse(
                command.status(), command.currentStage(),
                ReportExportStageView.forCommand(command.status(), command.currentStage()));
    }

    @GetMapping("/report-exports/{batchId}/excel")
    public void download(@PathVariable String batchId, HttpServletResponse response) throws IOException {
        downloadDaily(batchId, response);
    }

    @GetMapping("/report-exports/{batchId}/daily")
    public void downloadDaily(@PathVariable String batchId, HttpServletResponse response) throws IOException {
        downloadDaily(batchId, response, false);
    }

    @GetMapping("/report-exports/{batchId}/daily-raw")
    public void downloadRawDaily(@PathVariable String batchId, HttpServletResponse response) throws IOException {
        downloadDaily(batchId, response, true);
    }

    private void downloadDaily(String batchId, HttpServletResponse response, boolean rawFieldValues) throws IOException {
        ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到导出批次");
        }
        if (!"SUCCEEDED".equals(command.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "批次尚未完成");
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(exportFilename(rawFieldValues), StandardCharsets.UTF_8).build().toString());
        if (rawFieldValues) {
            reportExportExcelService.streamRawDaily(batchId, response.getOutputStream());
        } else {
            reportExportExcelService.stream(batchId, response.getOutputStream());
        }
    }

    @GetMapping("/report-exports/{batchId}/weekly")
    public void downloadWeekly(@PathVariable String batchId, HttpServletResponse response) throws IOException {
        ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
        if (command == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到导出批次");
        if (!"SUCCEEDED".equals(command.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "批次尚未完成");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename("周期周报-" + EXPORT_FILENAME_TIME.format(LocalDateTime.now(clock)) + ".xlsx", StandardCharsets.UTF_8).build().toString());
        reportExportExcelService.streamWeekly(batchId, response.getOutputStream());
    }

    private String exportFilename() {
        return exportFilename(false);
    }

    private String exportFilename(boolean rawFieldValues) {
        LocalDateTime time = LocalDateTime.now(clock);
        return (rawFieldValues ? "日报明细-未脱敏-" : "日报明细-") + EXPORT_FILENAME_TIME.format(time) + ".xlsx";
    }

    public record ReportExportProgressResponse(String status, String currentStage, List<ReportExportStageView> stageViews) {}
}
