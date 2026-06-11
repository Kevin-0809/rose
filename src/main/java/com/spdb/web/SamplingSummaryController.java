package com.spdb.web;

import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SamplingSummarySearchCriteria;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SamplingSummaryController {
    private final SampleQueryService sampleQueryService;

    public SamplingSummaryController(SampleQueryService sampleQueryService) {
        this.sampleQueryService = sampleQueryService;
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
}
