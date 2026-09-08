package com.spdb.web;
import com.spdb.message.AnaMessageSendService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;
@Controller public class AnaMessageSendController { private final AnaMessageSendService service; public AnaMessageSendController(AnaMessageSendService s){service=s;}
 @GetMapping("/messages/ana-send") public String page(Model m){m.addAttribute("active","ana-send");m.addAttribute("stats",service.stats());return "messages/ana-send";}
 @PostMapping("/messages/ana-send/start") public String start(@RequestParam(defaultValue="528") String target,@RequestParam(defaultValue="1") int concurrency,@RequestParam(defaultValue="60") int rangeMinutes,@RequestParam(defaultValue="2") int retries,@RequestParam(defaultValue="15") int timeout){service.start(target,concurrency,rangeMinutes,retries,timeout);return "redirect:/messages/ana-send";}
 @PostMapping("/messages/ana-send/stop") public String stop(){service.stop();return "redirect:/messages/ana-send";}
 @PostMapping("/messages/ana-send/reset") public String reset(@RequestParam(defaultValue="all") String scope){try{int n=service.reset(scope);return "redirect:/messages/ana-send?resetCount="+n;}catch(IllegalStateException e){return "redirect:/messages/ana-send?resetError="+URLEncoder.encode(String.valueOf(e.getMessage()),StandardCharsets.UTF_8);}}
}
