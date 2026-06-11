package com.spdb.web;

import com.spdb.sampling.SamplingCommandForm;
import com.spdb.sampling.SamplingCommandSearchCriteria;
import com.spdb.sampling.SamplingCommandService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SamplingCommandController {
    private final SamplingCommandService samplingCommandService;

    public SamplingCommandController(SamplingCommandService samplingCommandService) {
        this.samplingCommandService = samplingCommandService;
    }

    @GetMapping("/sampling/commands")
    public String commands(@RequestParam(required = false) String batchId,
                           @RequestParam(required = false) String origCdate,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size,
                           Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        SamplingCommandSearchCriteria criteria = new SamplingCommandSearchCriteria(batchId, origCdate, status);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", samplingCommandService.search(criteria, params));
        model.addAttribute("form", new SamplingCommandForm(null, null));
        model.addAttribute("active", "sampling-commands");
        return "sampling/commands";
    }

    @PostMapping("/sampling/commands")
    public String create(@RequestParam String origCdate,
                         @RequestParam(required = false) String remark,
                         RedirectAttributes redirectAttributes) {
        try {
            String batchId = samplingCommandService.createCommand(new SamplingCommandForm(
                    origCdate, remark
            ));
            redirectAttributes.addFlashAttribute("message", "采样批次已提交：" + batchId);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/sampling/commands";
    }
}
