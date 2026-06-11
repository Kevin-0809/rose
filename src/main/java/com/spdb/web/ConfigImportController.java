package com.spdb.web;

import com.spdb.config.ConfigImportBatchResult;
import com.spdb.config.ConfigImportFile;
import com.spdb.config.ConfigImportResult;
import com.spdb.config.ConfigImportService;
import com.spdb.config.ParsedConfigImport;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Controller
public class ConfigImportController {
    private static final String DEFAULT_SERVICE_CODE = "S030030014FcyCollCrspBnkLkgQry";

    private final ConfigImportService importService;

    public ConfigImportController(ConfigImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/config/import")
    public String page(Model model) {
        prepare(model);
        return "config/import";
    }

    @PostMapping("/config/import/preview")
    public String preview(@RequestParam("file") MultipartFile[] files,
                          @RequestParam String serviceCode,
                          @RequestParam(required = false) String moduleName,
                          @RequestParam(required = false) String owner,
                          Model model) throws IOException {
        prepare(model);
        model.addAttribute("serviceCode", serviceCode);
        model.addAttribute("moduleName", moduleName);
        model.addAttribute("owner", owner);
        if (isEmpty(files)) {
            model.addAttribute("errorMessage", "请选择Excel文件");
            return "config/import";
        }
        List<ConfigImportFile> tempFiles = copyToTempFiles(files);
        try {
            List<ParsedConfigImport> previews = importService.previewWorkbooks(tempFiles, serviceCode, moduleName, owner);
            model.addAttribute("previews", previews);
            if (!previews.isEmpty()) {
                model.addAttribute("preview", previews.get(0));
            }
        } catch (RuntimeException | IOException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        } finally {
            deleteTempFiles(tempFiles);
        }
        return "config/import";
    }

    @PostMapping("/config/import/confirm")
    public String confirm(@RequestParam("file") MultipartFile[] files,
                          @RequestParam String serviceCode,
                          @RequestParam(required = false) String moduleName,
                          @RequestParam(required = false) String owner,
                          Model model) throws IOException {
        prepare(model);
        model.addAttribute("serviceCode", serviceCode);
        model.addAttribute("moduleName", moduleName);
        model.addAttribute("owner", owner);
        if (isEmpty(files)) {
            model.addAttribute("errorMessage", "请选择Excel文件");
            return "config/import";
        }
        List<ConfigImportFile> tempFiles = copyToTempFiles(files);
        try {
            ConfigImportBatchResult result = importService.importWorkbooks(tempFiles, serviceCode, moduleName, owner);
            List<ParsedConfigImport> previews = result.results().stream().map(ConfigImportResult::parsed).toList();
            model.addAttribute("batchResult", result);
            model.addAttribute("previews", previews);
            if (!previews.isEmpty()) {
                model.addAttribute("preview", previews.get(0));
            }
        } catch (RuntimeException | IOException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        } finally {
            deleteTempFiles(tempFiles);
        }
        return "config/import";
    }

    private void prepare(Model model) {
        model.addAttribute("active", "configImport");
        if (!model.containsAttribute("serviceCode")) {
            model.addAttribute("serviceCode", DEFAULT_SERVICE_CODE);
        }
    }

    private Path copyToTempFile(MultipartFile file) throws IOException {
        String suffix = ".xlsx";
        String name = file.getOriginalFilename();
        if (StringUtils.hasText(name) && name.toLowerCase().endsWith(".xls")) {
            suffix = ".xls";
        }
        Path tempFile = Files.createTempFile("rose-config-import-", suffix);
        file.transferTo(tempFile);
        return tempFile;
    }

    private List<ConfigImportFile> copyToTempFiles(MultipartFile[] files) throws IOException {
        List<ConfigImportFile> tempFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                tempFiles.add(new ConfigImportFile(copyToTempFile(file), file.getOriginalFilename()));
            }
        }
        return tempFiles;
    }

    private boolean isEmpty(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return true;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void deleteTempFiles(List<ConfigImportFile> files) throws IOException {
        for (ConfigImportFile file : files) {
            Files.deleteIfExists(file.path());
        }
    }
}
