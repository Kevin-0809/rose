package com.spdb.web;

import com.spdb.sample.SampleExcelExportService;
import com.spdb.sample.SampleQueryService;
import com.spdb.sample.SampleSearchCriteria;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class SampleController {
    private static final String TRANSACTION_DIFF = "RETURN_CODE";
    private static final String FIELD_DIFF = "FIELD_DIFF";

    private final SampleQueryService sampleQueryService;
    private final SampleExcelExportService sampleExcelExportService;

    public SampleController(SampleQueryService sampleQueryService, SampleExcelExportService sampleExcelExportService) {
        this.sampleQueryService = sampleQueryService;
        this.sampleExcelExportService = sampleExcelExportService;
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
        prepareExcel(response, "交易级差异.xlsx");
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
        model.addAttribute("result", sampleQueryService.details(criteria, params));
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
        prepareExcel(response, "字段级差异.xlsx");
        sampleExcelExportService.streamFieldDiffExport(sampleQueryService, criteria, response.getOutputStream());
    }

    @GetMapping("/samples/groups")
    public String groups(@RequestParam(required = false) String batchId,
                         @RequestParam(required = false) String origCdate,
                         @RequestParam(required = false) String sampleType,
                         @RequestParam(required = false) String tranCode,
                         @RequestParam(required = false) String serviceCode,
                         @RequestParam(required = false) String messageType,
                         @RequestParam(required = false) String configStatus,
                         @RequestParam(required = false) String mappingStatus,
                         @RequestParam(required = false) String semanticFieldName,
                         @RequestParam(required = false) String owner,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size,
                         Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, sampleType, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, null
        );
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", sampleQueryService.groups(criteria, params));
        model.addAttribute("active", "sample-groups");
        return "samples/groups";
    }

    @GetMapping("/samples/groups/export")
    public void exportGroups(@RequestParam(required = false) String batchId,
                             @RequestParam(required = false) String origCdate,
                             @RequestParam(required = false) String sampleType,
                             @RequestParam(required = false) String tranCode,
                             @RequestParam(required = false) String serviceCode,
                             @RequestParam(required = false) String messageType,
                             @RequestParam(required = false) String configStatus,
                             @RequestParam(required = false) String mappingStatus,
                             @RequestParam(required = false) String semanticFieldName,
                             @RequestParam(required = false) String owner,
                             HttpServletResponse response) throws IOException {
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, origCdate, sampleType, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, null
        );
        prepareExcel(response, "采样分组.xlsx");
        sampleExcelExportService.streamGroups(sampleQueryService, criteria, response.getOutputStream());
    }

    @GetMapping("/samples/details")
    public String details(@RequestParam(required = false) String batchId,
                          @RequestParam(required = false) String origCdate,
                          @RequestParam(required = false) String sampleType,
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
                batchId, origCdate, sampleType, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, tranSeqNo
        );
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", sampleQueryService.details(criteria, params));
        model.addAttribute("active", "sample-details");
        return "samples/details";
    }

    @GetMapping("/samples/details/export")
    public void exportDetails(@RequestParam(required = false) String batchId,
                              @RequestParam(required = false) String origCdate,
                              @RequestParam(required = false) String sampleType,
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
                batchId, origCdate, sampleType, tranCode, serviceCode, messageType, configStatus, mappingStatus,
                semanticFieldName, owner, tranSeqNo
        );
        prepareExcel(response, "采样明细.xlsx");
        sampleExcelExportService.streamDetails(sampleQueryService, criteria, response.getOutputStream());
    }

    @GetMapping("/samples/detail-fields/export")
    public void exportDetailFields(@RequestParam(required = false) String batchId,
                                   @RequestParam(required = false) String messageType,
                                   @RequestParam(required = false) String mappingStatus,
                                   @RequestParam(required = false) String semanticFieldName,
                                   @RequestParam(required = false) String tranSeqNo,
                                   @RequestParam(required = false) String owner,
                                   HttpServletResponse response) throws IOException {
        SampleSearchCriteria criteria = new SampleSearchCriteria(
                batchId, null, null, null, null, messageType, null, mappingStatus,
                semanticFieldName, owner, tranSeqNo
        );
        prepareExcel(response, "样本字段明细.xlsx");
        sampleExcelExportService.streamDetailFields(sampleQueryService, criteria, response.getOutputStream());
    }

    private void prepareExcel(HttpServletResponse response, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }
}
