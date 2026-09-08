package com.spdb.web;
import com.spdb.message.AnaMessageSendService;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;
@Controller public class AnaMessageSendController { private final AnaMessageSendService service; public AnaMessageSendController(AnaMessageSendService s){service=s;}
 @GetMapping("/messages/ana-send") public String page(Model m){m.addAttribute("active","ana-send");m.addAttribute("stats",service.stats());return "messages/ana-send";}
 @PostMapping("/messages/ana-send/start") public String start(@RequestParam(defaultValue="528") String target,@RequestParam(defaultValue="1") int concurrency,@RequestParam(defaultValue="100") int batch,@RequestParam(defaultValue="2") int retries,@RequestParam(defaultValue="15") int timeout){service.start(target,concurrency,batch,retries,timeout);return "redirect:/messages/ana-send";}
 @PostMapping("/messages/ana-send/stop") public String stop(){service.stop();return "redirect:/messages/ana-send";}
}
