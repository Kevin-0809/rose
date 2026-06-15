package com.spdb.web;

import com.spdb.message.MessageFlowLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
}
