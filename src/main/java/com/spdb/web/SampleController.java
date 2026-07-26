package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import com.spdb.sample.TransactionDiffTrackingExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
public class SampleController {
    private static final String TRANSACTION_DIFF = "RETURN_CODE";
    private static final String FIELD_DIFF = "FIELD_DIFF";
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter TRACKING_EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String FIELD_DIFF_EXPORT_FILENAME_PREFIX = "\u5b57\u6bb5\u7ea7\u5dee\u5f02_";

    private final SampleQueryService sampleQueryService;
    private final SampleExcelExportService sampleExcelExportService;
    private final TransactionDiffTrackingExportService transactionDiffTrackingExportService;

    public SampleController(SampleQueryService sampleQueryService, SampleExcelExportService sampleExcelExportService) {
        this(sampleQueryService, sampleExcelExportService, null);
    }

    @Autowired
    public SampleController(SampleQueryService sampleQueryService, SampleExcelExportService sampleExcelExportService,
                            TransactionDiffTrackingExportService transactionDiffTrackingExportService) {
        this.sampleQueryService = sampleQueryService;
        this.sampleExcelExportService = sampleExcelExportService;
        this.transactionDiffTrackingExportService = transactionDiffTrackingExportService;
    }

    @GetMapping("/samples/transaction-diffs")
    public String transactionDiffs(@RequestParam(required = false) String batchId,
                                   @RequestParam(required = false) String origCdate,
                                   @RequestParam(required = false) String tranCode,
                                   @RequestParam(required = false) String serviceCode,
                                   @RequestParam(required = false) String messageType,
                                   @RequestParam(required = false) String configStatus,
                                   @RequestParam(required = false) String owner,
                                   @RequestParam(required = false) String tranSeqNo,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size,
                                   Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, TRANSACTION_DIFF, tranCode, serviceCode, messageType, configStatus, null,
                null, owner, tranSeqNo
        );
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", sampleQueryService.transactionDiffs(criteria, params));
        model.addAttribute("active", "transaction-diffs");
        return "samples/transaction-diffs";
    }

    @GetMapping("/samples/transaction-diffs/export")
    public void exportTransactionDiffs(@RequestParam(required = false) String batchId,
                                       @RequestParam(required = false) String origCdate,
                                       @RequestParam(required = false) String tranCode,
                                       @RequestParam(required = false) String serviceCode,
                                       @RequestParam(required = false) String messageType,
                                       @RequestParam(required = false) String configStatus,
                                       @RequestParam(required = false) String owner,
                                       @RequestParam(required = false) String tranSeqNo,
                                       HttpServletResponse response) throws IOException {
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, TRANSACTION_DIFF, tranCode, serviceCode, messageType, configStatus, null,
                null, owner, tranSeqNo
        );
        prepareZip(response, "transdiff_" + LocalDateTime.now().format(EXPORT_TIMESTAMP) + ".zip");
        sampleExcelExportService.streamTransactionDiffExport(sampleQueryService, criteria, response.getOutputStream());
    }

    @GetMapping("/samples/field-diffs")
    public String fieldDiffs(@RequestParam(required = false) String batchId,
                             @RequestParam(required = false) String origCdate,
                             @RequestParam(required = false) String tranCode,
                             @RequestParam(required = false) String serviceCode,
                             @RequestParam(required = false) String messageType,
                             @RequestParam(required = false) String configStatus,
                             @RequestParam(required = false) String mappingStatus,
                             @RequestParam(required = false) String semanticFieldName,
                             @RequestParam(required = false) String owner,
                             @RequestParam(required = false) String tranSeqNo,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, FIELD_DIFF, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, tranSeqNo
        );
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", sampleQueryService.fieldDiffs(criteria, params));
        model.addAttribute("active", "field-diffs");
        return "samples/field-diffs";
    }

    @GetMapping("/samples/field-diffs/export")
    public void exportFieldDiffs(@RequestParam(required = false) String batchId,
                                 @RequestParam(required = false) String origCdate,
                                 @RequestParam(required = false) String tranCode,
                                 @RequestParam(required = false) String serviceCode,
                                 @RequestParam(required = false) String messageType,
                                 @RequestParam(required = false) String configStatus,
                                 @RequestParam(required = false) String mappingStatus,
                                 @RequestParam(required = false) String semanticFieldName,
                                 @RequestParam(required = false) String owner,
                                 @RequestParam(required = false) String tranSeqNo,
                                 HttpServletResponse response) throws IOException {
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, FIELD_DIFF, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, tranSeqNo
        );
        prepareZip(response, "fielddiff_" + LocalDateTime.now().format(EXPORT_TIMESTAMP) + ".zip");
        sampleExcelExportService.streamFieldDiffZipExport(sampleQueryService, criteria, response.getOutputStream());
    }

    @GetMapping("/samples/transaction-diffs/tracking-export")
    public void exportTransactionDiffTracking(@RequestParam(required = false) String batchId,
                                              HttpServletResponse response) throws IOException {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u9009\u62e9\u6279\u6b21\u540e\u5bfc\u51fa");
        }
        prepareText(response, "trandiff_hf_" + LocalDateTime.now().format(TRACKING_EXPORT_TIMESTAMP) + ".txt");
        transactionDiffTrackingExportService.export(batchId.trim(), response.getOutputStream());
    }


    private void prepareExcel(HttpServletResponse response, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    private void prepareZip(HttpServletResponse response, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    private void prepareText(HttpServletResponse response, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }
}
