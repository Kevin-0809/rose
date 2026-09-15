package com.spdb.web;

import com.spdb.migration.MigrationTranCodeCommandForm;
import com.spdb.replay.ReplayVolumeCheckBatch;
import com.spdb.replay.ReplayVolumeCheckResult;
import com.spdb.replay.ReplayVolumeCheckService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
        model.addAttribute("baseDate", current == null ? null : current.baseDate());
        model.addAttribute("collectionType", current == null ? null : current.collectionType());
        if (current != null) {
            model.addAttribute("noVolumeDetails", service.details(current.checkId(), com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_VOLUME, PageRequestParams.of(noVolumePage, noVolumeSize == null ? size : noVolumeSize)));
            model.addAttribute("noMappingDetails", service.details(current.checkId(), com.spdb.replay.ReplayVolumeCheckDetailStatus.NO_MAPPING, PageRequestParams.of(noMappingPage, noMappingSize == null ? size : noMappingSize)));
            model.addAttribute("cleanupDetails", service.cleanupDetails(current.checkId(), PageRequestParams.of(cleanupPage, cleanupSize == null ? size : cleanupSize)));
            model.addAttribute("canConfirm", service.canConfirm(current.checkId()));
        }
        return "config/replay-volume-check";
    }

    public String start(int sampleSize, Model model) {
        return start(sampleSize, null, MigrationTranCodeCommandForm.COLLECTION_TYPE_QUERY, model);
    }

    @PostMapping("/config/replay-volume-check")
    public String start(@RequestParam(required = false, defaultValue = "100") int sampleSize,
                        @RequestParam(required = false) String baseDate,
                        @RequestParam(required = false) String collectionType, Model model) {
        try {
            ReplayVolumeCheckResult result = service.check(sampleSize, parseBaseDate(baseDate), parseCollectionType(collectionType));
            return "redirect:/config/replay-volume-check/" + result.batch().checkId();
        } catch (IllegalArgumentException ex) {
            model.addAttribute("active", "replay-volume-check");
            model.addAttribute("sampleSize", sampleSize);
            model.addAttribute("baseDate", baseDate);
            model.addAttribute("collectionType", collectionType);
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("history", PagedResult.of(List.of(), 0, PageRequestParams.of(null, null)));
            return "config/replay-volume-check";
        }
    }

    static LocalDate parseBaseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请填写铺底日期");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("日期格式不正确，请使用 yyyy-MM-dd");
        }
    }

    static String parseCollectionType(String value) {
        String collectionType = ReplayVolumeCheckService.normalizeCollectionType(value);
        if (collectionType == null) {
            throw new IllegalArgumentException("请选择采集方式（查询或账务）");
        }
        return collectionType;
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
