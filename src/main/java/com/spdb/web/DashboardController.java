package com.spdb.web;

import com.spdb.sample.SampleQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    private final SampleQueryService sampleQueryService;

    public DashboardController(SampleQueryService sampleQueryService) {
        this.sampleQueryService = sampleQueryService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("stats", sampleQueryService.summary());
        model.addAttribute("summaryChart", sampleQueryService.summaryChart(10));
        model.addAttribute("active", "home");
        return "home";
    }
}
