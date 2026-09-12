package com.spdb.web;

import com.spdb.message.AnaMessageSendService;
import com.spdb.message.AnaMessageSendTaskLauncher;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
public class AnaMessageSendController {

    private final AnaMessageSendService service;
    private final AnaMessageSendTaskLauncher launcher;

    public AnaMessageSendController(AnaMessageSendService service, AnaMessageSendTaskLauncher launcher) {
        this.service = service;
        this.launcher = launcher;
    }

    @GetMapping("/messages/ana-send")
    public String page(Model model) {
        model.addAttribute("active", "ana-send");
        model.addAttribute("stats", service.liveStats());
        model.addAttribute("running", service.isRunning());
        return "messages/ana-send";
    }

    @GetMapping("/messages/ana-send/status")
    @ResponseBody
    public Map<String, Object> status() {
        return service.liveStats();
    }

    @PostMapping("/messages/ana-send/start")
    public String start(@RequestParam(defaultValue = "528") String target,
                        @RequestParam(defaultValue = "1") int concurrency,
                        @RequestParam(defaultValue = "60") int rangeMinutes,
                        @RequestParam(defaultValue = "2") int retries,
                        @RequestParam(defaultValue = "15") int timeout) {
        if (!service.isRunning()) {
            launcher.launch(target, concurrency, rangeMinutes, retries, timeout);
        }
        return "redirect:/messages/ana-send";
    }

    @PostMapping("/messages/ana-send/stop")
    public String stop() {
        service.stop();
        return "redirect:/messages/ana-send";
    }

    @PostMapping("/messages/ana-send/reset")
    public String reset(@RequestParam(defaultValue = "all") String scope) {
        try {
            int n = service.reset(scope);
            return "redirect:/messages/ana-send?resetCount=" + n;
        } catch (IllegalStateException e) {
            return "redirect:/messages/ana-send?resetError="
                    + URLEncoder.encode(String.valueOf(e.getMessage()), StandardCharsets.UTF_8);
        }
    }
}
