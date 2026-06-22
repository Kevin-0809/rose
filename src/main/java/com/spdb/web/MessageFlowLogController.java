package com.spdb.web;

import com.spdb.message.MessageFlowLogEntryForm;
import com.spdb.message.MessageFlowLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class MessageFlowLogController {
    private final MessageFlowLogService messageFlowLogService;

    public MessageFlowLogController(MessageFlowLogService messageFlowLogService) {
        this.messageFlowLogService = messageFlowLogService;
    }

    @GetMapping("/messages/flow-logs")
    public String page(@RequestParam(required = false) String query, Model model) {
        model.addAttribute("active", "message-flow-logs");
        model.addAttribute("query", query);
        model.addAttribute("transId", MessageFlowLogService.normalizeTransId(query));
        model.addAttribute("rows", messageFlowLogService.search(query));
        return "messages/flow-logs";
    }

    @GetMapping("/messages/flow-logs/new")
    public String newEntryPage(Model model) {
        model.addAttribute("active", "message-flow-log-entry");
        model.addAttribute("form", MessageFlowLogEntryForm.empty());
        return "messages/flow-log-entry";
    }

    @PostMapping("/messages/flow-logs/new")
    public String createEntry(@ModelAttribute MessageFlowLogEntryForm form, Model model) {
        if (!form.hasRequiredRequestFields()) {
            model.addAttribute("active", "message-flow-log-entry");
            model.addAttribute("form", form);
            model.addAttribute("error", "来源IP、流水号、交易码、请求时间、请求报文不能为空");
            return "messages/flow-log-entry";
        }
        messageFlowLogService.saveEntry(form);
        String encoded = URLEncoder.encode(form.cleanTransId(), StandardCharsets.UTF_8).replace("+", "%20");
        return "redirect:/messages/flow-logs?query=" + encoded;
    }
}
