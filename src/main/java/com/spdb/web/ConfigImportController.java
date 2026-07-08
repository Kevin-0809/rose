package com.spdb.web;

import com.spdb.config.ConfigImportBatchResult;
import com.spdb.config.ConfigImportFile;
import com.spdb.config.ConfigImportService;
import com.spdb.config.TransactionListImportProgressRow;
import com.spdb.config.TransactionListImportTaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Controller
public class ConfigImportController {
    private final ConfigImportService importService;
    private final TransactionListImportTaskService listImportTaskService;

    public ConfigImportController(ConfigImportService importService,
                                  TransactionListImportTaskService listImportTaskService) {
        this.importService = importService;
        this.listImportTaskService = listImportTaskService;
    }

    @GetMapping("/config/import")
    public String page(Model model) {
        prepare(model);
        return "config/import";
    }

    @PostMapping("/config/import/list")
    public String importList(@RequestParam("listFile") MultipartFile listFile,
                             Model model) throws IOException {
        prepare(model);
        if (listFile == null || listFile.isEmpty()) {
            model.addAttribute("errorMessage", "请选择金融业务交易信息登记表Excel文件");
            return "config/import";
        }
        try {
            long taskId = listImportTaskService.createTask(copyToTempFile(listFile), listFile.getOriginalFilename());
            return "redirect:/config/import/list-tasks/" + taskId;
        } catch (RuntimeException | IOException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }
        return "config/import";
    }

    @GetMapping("/config/import/list-tasks/{id}")
    public String listImportProgressPage(@PathVariable long id, Model model) {
        prepare(model);
        model.addAttribute("progress", listImportTaskService.progress(id));
        return "config/import-list-progress";
    }

    @GetMapping("/config/import/list-tasks/{id}/progress")
    @ResponseBody
    public TransactionListImportProgressRow listImportProgressJson(@PathVariable long id) {
        return listImportTaskService.progress(id);
    }

    @PostMapping("/config/import/confirm")
    public String confirm(@RequestParam("file") MultipartFile[] files,
                          @RequestParam(required = false) String serviceCode,
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
            model.addAttribute("batchResult", result);
        } catch (RuntimeException | IOException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        } finally {
            deleteTempFiles(tempFiles);
        }
        return "config/import";
    }

    private void prepare(Model model) {
        model.addAttribute("active", "configImport");
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
