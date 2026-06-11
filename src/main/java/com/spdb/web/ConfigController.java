package com.spdb.web;

import com.spdb.config.ConfigQueryService;
import com.spdb.config.FieldSearchCriteria;
import com.spdb.config.RecordingConfigForm;
import com.spdb.config.RecordingSearchCriteria;
import com.spdb.config.TranSearchCriteria;
import com.spdb.domain.FieldMapping;
import com.spdb.domain.TranCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ConfigController {
    private final ConfigQueryService configQueryService;

    public ConfigController(ConfigQueryService configQueryService) {
        this.configQueryService = configQueryService;
    }

    @GetMapping("/config/trans")
    public String trans(@RequestParam(required = false) String tranCode,
                        @RequestParam(required = false) String serviceCode,
                        @RequestParam(required = false) String tranName,
                        @RequestParam(required = false) String moduleName,
                        @RequestParam(required = false) String owner,
                        @RequestParam(required = false) String isKeyTran,
                        @RequestParam(required = false) Integer page,
                        @RequestParam(required = false) Integer size,
                        Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        TranSearchCriteria criteria = new TranSearchCriteria(tranCode, serviceCode, tranName, moduleName, owner, isKeyTran);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", configQueryService.trans(criteria, params));
        model.addAttribute("tranForm", configQueryService.newTran());
        model.addAttribute("active", "trans");
        return "config/trans";
    }

    @GetMapping("/config/trans/{id}")
    public String editTran(@PathVariable Long id, Model model) {
        model.addAttribute("criteria", new TranSearchCriteria(null, null, null, null, null, null));
        model.addAttribute("result", configQueryService.trans(new TranSearchCriteria(null, null, null, null, null, null), PageRequestParams.of(1, 20)));
        model.addAttribute("tranForm", configQueryService.tran(id));
        model.addAttribute("active", "trans");
        return "config/trans";
    }

    @PostMapping("/config/trans")
    public String saveTran(@ModelAttribute TranCatalog tranCatalog) {
        configQueryService.saveTran(tranCatalog);
        return "redirect:/config/trans";
    }

    @PostMapping("/config/trans/{id}/delete")
    public String deleteTran(@PathVariable Long id) {
        configQueryService.deleteTran(id);
        return "redirect:/config/trans";
    }

    @GetMapping("/config/fields")
    public String fields(@RequestParam(required = false) String tranCode,
                         @RequestParam(required = false) String serviceCode,
                         @RequestParam(required = false) String stdFieldName,
                         @RequestParam(required = false) String sopFieldName,
                         @RequestParam(required = false) String soapFieldName,
                         @RequestParam(required = false) String bizjsonFieldName,
                         @RequestParam(required = false) String fieldCnName,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size,
                         Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        FieldSearchCriteria criteria = new FieldSearchCriteria(tranCode, serviceCode, stdFieldName, sopFieldName, soapFieldName, bizjsonFieldName, fieldCnName);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", configQueryService.fields(criteria, params));
        model.addAttribute("fieldForm", configQueryService.newField());
        model.addAttribute("active", "fields");
        return "config/fields";
    }

    @GetMapping("/config/fields/{id}")
    public String editField(@PathVariable Long id, Model model) {
        FieldSearchCriteria criteria = new FieldSearchCriteria(null, null, null, null, null, null, null);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", configQueryService.fields(criteria, PageRequestParams.of(1, 20)));
        model.addAttribute("fieldForm", configQueryService.field(id));
        model.addAttribute("active", "fields");
        return "config/fields";
    }

    @PostMapping("/config/fields")
    public String saveField(@ModelAttribute FieldMapping fieldMapping) {
        configQueryService.saveField(fieldMapping);
        return "redirect:/config/fields";
    }

    @PostMapping("/config/fields/{id}/delete")
    public String deleteField(@PathVariable Long id) {
        configQueryService.deleteField(id);
        return "redirect:/config/fields";
    }

    @GetMapping("/config/recording")
    public String recording(@RequestParam(required = false) String txnCode,
                            @RequestParam(required = false) Integer txnSwitch,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size,
                            Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        RecordingSearchCriteria criteria = new RecordingSearchCriteria(txnCode, txnSwitch);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", configQueryService.recordingConfigs(criteria, params));
        model.addAttribute("recordingForm", configQueryService.newRecordingConfig());
        model.addAttribute("globalSwitch", configQueryService.globalRecordingSwitch());
        model.addAttribute("active", "recording");
        return "config/recording";
    }

    @GetMapping("/config/recording/{id}")
    public String editRecording(@PathVariable Long id, Model model) {
        RecordingSearchCriteria criteria = new RecordingSearchCriteria(null, null);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", configQueryService.recordingConfigs(criteria, PageRequestParams.of(1, 20)));
        model.addAttribute("recordingForm", configQueryService.recordingConfig(id));
        model.addAttribute("globalSwitch", configQueryService.globalRecordingSwitch());
        model.addAttribute("active", "recording");
        return "config/recording";
    }

    @PostMapping("/config/recording")
    public String saveRecording(@ModelAttribute RecordingConfigForm recordingForm) {
        configQueryService.saveRecordingConfig(recordingForm);
        return "redirect:/config/recording";
    }

    @PostMapping("/config/recording/global")
    public String saveGlobalRecording(@RequestParam String configValue) {
        configQueryService.saveGlobalRecordingSwitch(configValue);
        return "redirect:/config/recording";
    }

    @PostMapping("/config/recording/{id}/delete")
    public String deleteRecording(@PathVariable Long id) {
        configQueryService.deleteRecordingConfig(id);
        return "redirect:/config/recording";
    }
}
