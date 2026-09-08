package com.spdb.web;

import com.spdb.replay.ReplayVolumeCheckBatch;
import com.spdb.replay.ReplayVolumeCheckResult;
import com.spdb.replay.ReplayVolumeCheckService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class ReplayVolumeCheckController {
    private final ReplayVolumeCheckService service;

    public ReplayVolumeCheckController(ReplayVolumeCheckService service) {
        this.service = service;
    }

    public String page(Integer page, Integer size, Model model) {
        return page(page, size, null, null, null, null, null, null, null, null, null, null, model);
    }

    @GetMapping("/config/replay-volume-check")
    public String page(@RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer historyPage,
                       @RequestParam(required = false) Integer historySize,
                       @RequestParam(required = false) Integer noVolumePage,
                       @RequestParam(required = false) Integer noVolumeSize,
                       @RequestParam(required = false) Integer noMappingPage,
                       @RequestParam(required = false) Integer noMappingSize,
                       @RequestParam(required = false) Integer cleanupPage,
                       @RequestParam(required = false) Integer cleanupSize,
                       @RequestParam(required = false) Long checkId,
                       @RequestParam(required = false) String status,
                       Model model) {
        PageRequestParams params = PageRequestParams.of(historyPage == null ? page : historyPage, historySize == null ? size : historySize);
        var history = service.history(params);
        ReplayVolumeCheckResult latest = service.latest();
        ReplayVolumeCheckBatch current = latest == null
                ? (history.rows().isEmpty() ? null : history.rows().get(0))
                : latest.batch();
        model.addAttribute("active", "replay-volume-check");
        model.addAttribute("history", history);
        model.addAttribute("currentBatch", current);
        model.addAttribute("sampleSize", current == null ? 100 : current.sampleSize());
        model.addAttribute("lookbackDays", current == null ? 30 : current.lookbackDays());
        if (current != null) {
            model.addAttribute("noVolumeDetails", service.details(current.checkId(), com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_VOLUME, PageRequestParams.of(noVolumePage, noVolumeSize == null ? size : noVolumeSize)));
            model.addAttribute("noMappingDetails", service.details(current.checkId(), com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_MAPPING, PageRequestParams.of(noMappingPage, noMappingSize == null ? size : noMappingSize)));
            model.addAttribute("cleanupDetails", service.cleanupDetails(current.checkId(), PageRequestParams.of(cleanupPage, cleanupSize == null ? size : cleanupSize)));
            model.addAttribute("canConfirm", service.canConfirm(current.checkId()));
        }
        return "config/replay-volume-check";
    }

    public String start(int sampleSize, Model model) {
        try {
            ReplayVolumeCheckResult result = service.check(sampleSize);
            return "redirect:/config/replay-volume-check/" + result.batch().checkId();
        } catch (IllegalArgumentException ex) {
            model.addAttribute("active", "replay-volume-check");
            model.addAttribute("sampleSize", sampleSize);
            model.addAttribute("lookbackDays", 30);
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("history", PagedResult.of(List.of(), 0, PageRequestParams.of(null, null)));
            return "config/replay-volume-check";
        }
    }

    @PostMapping("/config/replay-volume-check")
    public String start(@RequestParam(required = false, defaultValue = "100") int sampleSize,
                        @RequestParam(required = false, defaultValue = "30") int lookbackDays, Model model) {
        try {
            ReplayVolumeCheckResult result = service.check(sampleSize, lookbackDays);
            return "redirect:/config/replay-volume-check/" + result.batch().checkId();
        } catch (IllegalArgumentException ex) {
            model.addAttribute("active", "replay-volume-check");
            model.addAttribute("sampleSize", sampleSize);
            model.addAttribute("lookbackDays", lookbackDays);
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("history", PagedResult.of(List.of(), 0, PageRequestParams.of(null, null)));
            return "config/replay-volume-check";
        }
    }

    @PostMapping("/config/replay-volume-check/{checkId}/confirm")
    public String confirm(@PathVariable long checkId) {
        service.confirm(checkId);
        return "redirect:/config/replay-volume-check/" + checkId;
    }

    @GetMapping("/config/replay-volume-check/{checkId}")
    public String detail(@PathVariable long checkId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) Integer cleanupPage,
                         @RequestParam(required = false) Integer cleanupSize,
                         @RequestParam(required = false) Integer noVolumePage,
                         @RequestParam(required = false) Integer noVolumeSize,
                         @RequestParam(required = false) Integer noMappingPage,
                         @RequestParam(required = false) Integer noMappingSize,
                         Model model) {
        PageRequestParams detailParams = PageRequestParams.of(noVolumePage == null ? page : noVolumePage, noVolumeSize == null ? size : noVolumeSize);
        PageRequestParams noMappingParams = PageRequestParams.of(noMappingPage, noMappingSize == null ? size : noMappingSize);
        PageRequestParams cleanupParams = PageRequestParams.of(cleanupPage, cleanupSize == null ? size : cleanupSize);
        ReplayVolumeCheckResult refreshed = service.refresh(checkId);
        model.addAttribute("active", "replay-volume-check");
        model.addAttribute("batch", refreshed.batch());
        model.addAttribute("details", service.details(checkId, detailParams));
        model.addAttribute("noVolumeDetails", service.details(checkId, com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_VOLUME, detailParams));
        model.addAttribute("noMappingDetails", service.details(checkId, com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_MAPPING, noMappingParams));
        model.addAttribute("cleanupDetails", service.cleanupDetails(checkId, cleanupParams));
        model.addAttribute("canConfirm", service.canConfirm(checkId));
        return "config/replay-volume-check-detail";
    }

    public String detail(long checkId, Integer page, Integer size, Model model) {
        return detail(checkId, page, size, null, null, null, null, null, null, model);
    }

    public String detail(long checkId, Integer page, Integer size, Integer cleanupPage, Integer cleanupSize, Model model) {
        return detail(checkId, page, size, cleanupPage, cleanupSize, null, null, null, null, model);
    }

}
