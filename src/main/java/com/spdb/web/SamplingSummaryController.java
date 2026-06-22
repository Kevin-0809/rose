package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SamplingSummarySearchCriteria;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class SamplingSummaryController {
    private final SampleQueryService sampleQueryService;
    private final SampleExcelExportService sampleExcelExportService;

    public SamplingSummaryController(SampleQueryService sampleQueryService, SampleExcelExportService sampleExcelExportService) {
        this.sampleQueryService = sampleQueryService;
        this.sampleExcelExportService = sampleExcelExportService;
    }

    @GetMapping("/sampling/summaries")
    public String summaries(@RequestParam(required = false) String batchId,
                            @RequestParam(required = false) String origCdate,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size,
                            Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        SamplingSummarySearchCriteria criteria = new SamplingSummarySearchCriteria(batchId, origCdate);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", sampleQueryService.summaryHistory(criteria, params));
        model.addAttribute("summaryChart", sampleQueryService.summaryChart(20));
        model.addAttribute("active", "sampling-summaries");
        return "sampling/summaries";
    }

    @GetMapping("/sampling/summaries/report/export")
    public void exportServiceReport(@RequestParam(required = false) String batchId,
                                    @RequestParam(required = false) String origCdate,
                                    HttpServletResponse response) throws IOException {
        SamplingSummarySearchCriteria criteria = new SamplingSummarySearchCriteria(batchId, origCdate);
        String encoded = URLEncoder.encode("采样服务码维度汇报.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        sampleExcelExportService.streamServiceReport(sampleQueryService, criteria, response.getOutputStream());
    }
}
