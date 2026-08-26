package com.spdb.web;

import com.spdb.replay.ReplayTransactionCatalogForm;
import com.spdb.replay.ReplayTransactionCatalogSearch;
import com.spdb.replay.ReplayTransactionCatalogService;
import com.spdb.replay.ReplayTransactionCatalogImportService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Controller
public class ReplayTransactionCatalogController {
    private final ReplayTransactionCatalogService service;
    private final ReplayTransactionCatalogImportService importService;

    public ReplayTransactionCatalogController(ReplayTransactionCatalogService service) { this(service, null); }

    public ReplayTransactionCatalogController(ReplayTransactionCatalogService service,
                                              ReplayTransactionCatalogImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping("/config/replay-catalog")
    public String list(@RequestParam(required = false) String tranCode, @RequestParam(required = false) String tranName,
                       @RequestParam(required = false) String businessDomain, @RequestParam(required = false) String batchType,
                       @RequestParam(required = false) String replayRequired, @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size, Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        model.addAttribute("criteria", new ReplayTransactionCatalogSearch(tranCode, tranName, businessDomain, batchType, replayRequired));
        model.addAttribute("result", service.search(new ReplayTransactionCatalogSearch(tranCode, tranName, businessDomain, batchType, replayRequired), params));
        model.addAttribute("active", "replay-catalog");
        return "config/replay-catalog";
    }

    @PostMapping(value = "/config/replay-catalog/import", consumes = "multipart/form-data")
    public String importWorkbook(@RequestParam("file") MultipartFile file, Model model) throws IOException {
        if (file == null || file.isEmpty()) {
            model.addAttribute("importError", "请选择回放交易清单Excel文件");
        } else if (importService == null) {
            model.addAttribute("importError", "导入服务不可用");
        } else {
            try {
                int count = importService.importWorkbook(file.getBytes());
                model.addAttribute("importSuccess", "导入成功，共写入 " + count + " 条");
            } catch (RuntimeException | IOException ex) {
                model.addAttribute("importError", ex.getMessage());
            }
        }
        return list(null, null, null, null, null, 1, null, model);
    }

    @GetMapping("/config/replay-catalog/new")
    public String newForm(Model model) {
        model.addAttribute("form", new ReplayTransactionCatalogForm(null, null, null, "", null, null, "", null, null, "", null));
        model.addAttribute("active", "replay-catalog");
        return "config/replay-catalog-edit";
    }

    @GetMapping("/config/replay-catalog/{tranCode}/edit")
    public String edit(@PathVariable String tranCode, Model model) {
        model.addAttribute("form", service.form(tranCode));
        model.addAttribute("pathTranCode", tranCode);
        model.addAttribute("active", "replay-catalog");
        return "config/replay-catalog-edit";
    }

    @PostMapping("/config/replay-catalog")
    public String save(@ModelAttribute("form") ReplayTransactionCatalogForm form,
                       @RequestParam(required = false) String pathTranCode) {
        service.save(form, pathTranCode);
        return "redirect:/config/replay-catalog";
    }

    @PostMapping("/config/replay-catalog/{tranCode}/delete")
    public String delete(@PathVariable String tranCode) {
        service.delete(tranCode);
        return "redirect:/config/replay-catalog";
    }
}
